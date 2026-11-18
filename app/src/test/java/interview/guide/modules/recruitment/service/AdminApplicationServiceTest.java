package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.auth.repository.AdminUserRepository;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.mail.model.MailSecurity;
import interview.guide.modules.mail.service.MailOutboxService;
import interview.guide.modules.mail.service.MailSettingsService;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import interview.guide.modules.recruitment.model.ApplicationDetailDTO;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatsDTO;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.BatchReviewRequest;
import interview.guide.modules.recruitment.model.BatchReviewResult;
import interview.guide.modules.recruitment.model.OfflineInterviewRequest;
import interview.guide.modules.recruitment.model.OperatorType;
import interview.guide.modules.recruitment.repository.AnswerTelemetryRepository;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.ApplicationStatusLogRepository;
import interview.guide.modules.recruitment.repository.CandidateRepository;
import interview.guide.modules.recruitment.repository.ProctorEventRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("后台投递审核与线下面试安排服务测试")
class AdminApplicationServiceTest {

  @Mock
  private ApplicationRepository applicationRepository;

  @Mock
  private CandidateRepository candidateRepository;

  @Mock
  private JobPositionRepository jobPositionRepository;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private ProctorEventRepository proctorEventRepository;

  @Mock
  private AnswerTelemetryRepository answerTelemetryRepository;

  @Mock
  private interview.guide.modules.recruitment.repository.WrittenTestRepository writtenTestRepository;

  @Mock
  private ApplicationStatusLogRepository statusLogRepository;

  @Mock
  private InterviewScheduleRepository scheduleRepository;

  @Mock
  private AdminUserRepository adminUserRepository;

  @Mock
  private ApplicationStateMachine stateMachine;

  @Mock
  private MailOutboxService mailOutboxService;

  @Mock
  private MailSettingsService mailSettingsService;

  @Mock
  private FileStorageService fileStorageService;

  @Mock
  private TransactionalExecutor transactionalExecutor;

  private AdminApplicationService service;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = new ObjectMapper();
    service = new AdminApplicationService(
        applicationRepository, candidateRepository, jobPositionRepository, resumeRepository,
        proctorEventRepository, answerTelemetryRepository, writtenTestRepository, new WrittenTestCodec(mapper),
        new ProctorRiskService(proctorEventRepository, answerTelemetryRepository, new ProctorRiskCalculator()),
        statusLogRepository, scheduleRepository, adminUserRepository,
        stateMachine, mailOutboxService, mailSettingsService, fileStorageService,
        transactionalExecutor, mapper);
  }

  @Test
  @DisplayName("仅 AI_PASSED 状态允许 HR 审核通过，AI 已淘汰的投递不可被人工改判")
  void enforcesHrReviewRules() {
    ApplicationEntity aiPassed = new ApplicationEntity();
    aiPassed.setId(1L);
    aiPassed.setStatus(ApplicationStatus.AI_PASSED);

    ApplicationEntity aiRejected = new ApplicationEntity();
    aiRejected.setId(2L);
    aiRejected.setStatus(ApplicationStatus.AI_REJECTED);

    when(applicationRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(aiPassed));
    when(applicationRepository.findByIdForUpdate(2L)).thenReturn(Optional.of(aiRejected));

    service.review(1L, true, 9L, "技术功底扎实");
    verify(stateMachine).transit(eq(aiPassed), eq(ApplicationStatus.HR_APPROVED), eq(OperatorType.HR), eq(9L), any());

    assertThatThrownBy(() -> service.review(2L, true, 9L, "尝试捞人"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.APPLICATION_STATUS_INVALID.getCode());

    assertThatThrownBy(() -> service.review(2L, false, 9L, "重复淘汰"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.APPLICATION_STATUS_INVALID.getCode());
  }

  @Test
  @DisplayName("批量审核按单条事务隔离，部分失败不影响其余记录成功提交")
  void batchReviewIsolatesFailures() {
    ApplicationEntity ok = new ApplicationEntity();
    ok.setId(10L);
    ok.setStatus(ApplicationStatus.AI_PASSED);

    ApplicationEntity invalid = new ApplicationEntity();
    invalid.setId(11L);
    invalid.setStatus(ApplicationStatus.INTERVIEWING);

    when(applicationRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(ok));
    when(applicationRepository.findByIdForUpdate(11L)).thenReturn(Optional.of(invalid));
    doAnswer(inv -> {
      ((Runnable) inv.getArgument(0)).run();
      return null;
    }).when(transactionalExecutor).run(any());

    BatchReviewResult result = service.batchReview(new BatchReviewRequest(List.of(10L, 11L), true, "批量通过"), 9L);

    assertThat(result.succeeded()).containsExactly(10L);
    assertThat(result.failed()).hasSize(1);
    assertThat(result.failed().getFirst().id()).isEqualTo(11L);
  }

  @Test
  @DisplayName("安排线下面试创建日程记录、重置候选人待确认状态并流转至 OFFLINE_SCHEDULED")
  void schedulesOfflineInterviewAndResetsConfirmState() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(20L);
    app.setCandidateId(5L);
    app.setJobId(3L);
    app.setResumeId(8L);
    app.setStatus(ApplicationStatus.HR_APPROVED);

    JobPositionEntity job = new JobPositionEntity();
    job.setId(3L);
    job.setTitle("算法工程师");

    when(applicationRepository.findByIdForUpdate(20L)).thenReturn(Optional.of(app));
    when(applicationRepository.findById(20L)).thenReturn(Optional.of(app));
    when(jobPositionRepository.findById(3L)).thenReturn(Optional.of(job));
    when(scheduleRepository.findByApplicationId(20L)).thenReturn(Optional.empty());
    when(mailSettingsService.resolve()).thenReturn(
        new ResolvedMailConfig(true, "smtp.example.com", 465, "hr", "pwd", MailSecurity.SSL,
            "hr@example.com", "HR", "星辰科技", "http://localhost:5173", null));
    doAnswer(inv -> {
      ApplicationEntity target = inv.getArgument(0);
      target.setStatus(inv.getArgument(1));
      return null;
    }).when(stateMachine).transit(any(), any(), any(), any(), any());

    LocalDateTime interviewTime = LocalDateTime.now().plusDays(2);
    OfflineInterviewRequest req = new OfflineInterviewRequest(
        interviewTime, 60, " 北京市海淀区科技园 A 座 8F ", "王总监", "请携带身份证件", 2);

    ApplicationDetailDTO detail = service.scheduleOffline(20L, 9L, req);

    ArgumentCaptor<InterviewScheduleEntity> captor = ArgumentCaptor.forClass(InterviewScheduleEntity.class);
    verify(scheduleRepository).save(captor.capture());
    assertThat(captor.getValue().getCompanyName()).isEqualTo("星辰科技");
    assertThat(captor.getValue().getLocationAddress()).isEqualTo("北京市海淀区科技园 A 座 8F");
    assertThat(captor.getValue().getCandidateConfirmStatus()).isEqualTo(CandidateApplicationService.CONFIRM_PENDING);
    assertThat(detail.status()).isEqualTo(ApplicationStatus.OFFLINE_SCHEDULED);
  }

  @Test
  @DisplayName("招聘漏斗按 6 个累积阶段精确汇总转化人数")
  void computesSixStageCumulativeFunnel() {
    Map<ApplicationStatus, Long> counts = new EnumMap<>(ApplicationStatus.class);
    counts.put(ApplicationStatus.SCREEN_REJECTED, 4L);
    counts.put(ApplicationStatus.INTERVIEW_INVITED, 2L);
    counts.put(ApplicationStatus.AI_REJECTED, 3L);
    counts.put(ApplicationStatus.AI_PASSED, 2L);
    counts.put(ApplicationStatus.HR_APPROVED, 1L);
    counts.put(ApplicationStatus.OFFLINE_CONFIRMED, 2L);

    List<ApplicationStatsDTO.FunnelStage> funnel = AdminApplicationService.funnel(counts, 14L);

    assertThat(funnel).extracting(ApplicationStatsDTO.FunnelStage::count)
        .containsExactly(14L, 10L, 8L, 5L, 3L, 2L);
  }
}
