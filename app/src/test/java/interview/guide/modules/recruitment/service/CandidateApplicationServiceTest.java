package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.model.JobStatus;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateApplicationDTO;
import interview.guide.modules.recruitment.model.CandidateEntity;
import interview.guide.modules.recruitment.model.CandidateStage;
import interview.guide.modules.recruitment.model.OperatorType;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.CandidateRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeUploadService;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
@DisplayName("候选人投递与线下面试确认服务测试")
class CandidateApplicationServiceTest {

  @Mock
  private ApplicationRepository applicationRepository;

  @Mock
  private CandidateRepository candidateRepository;

  @Mock
  private JobPositionRepository jobPositionRepository;

  @Mock
  private InterviewScheduleRepository scheduleRepository;

  @Mock
  private ResumeUploadService resumeUploadService;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private ApplicationStateMachine stateMachine;

  @Mock
  private ApplicationWorkflowService workflowService;

  @Mock
  private TransactionalExecutor transactionalExecutor;

  private CandidateApplicationService service;

  @BeforeEach
  void setUp() {
    service = new CandidateApplicationService(
        applicationRepository, candidateRepository, jobPositionRepository, scheduleRepository,
        resumeUploadService, resumeRepository, stateMachine, workflowService, transactionalExecutor);
  }

  private JobPositionEntity openJob(boolean screeningEnabled, InterviewMode mode) {
    JobPositionEntity job = new JobPositionEntity();
    job.setId(5L);
    job.setTitle("高级 Java 工程师");
    job.setStatus(JobStatus.OPEN);
    job.setScreeningEnabled(screeningEnabled);
    job.setInterviewMode(mode);
    job.setQuestionCount(8);
    job.setTimeLimitMinutes(45);
    return job;
  }

  private CandidateEntity candidate(Long id) {
    CandidateEntity entity = new CandidateEntity();
    entity.setId(id);
    entity.setEmail("alice@example.com");
    return entity;
  }

  @Test
  @DisplayName("默认开启 AI 初筛时，投递成功后流转至 SCREENING，并在 BOTH 模式下向候选人暴露文字与语音双选项")
  void applyEntersScreeningByDefaultAndExposesBothModes() {
    JobPositionEntity job = openJob(true, InterviewMode.BOTH);
    when(jobPositionRepository.findById(5L)).thenReturn(Optional.of(job));
    when(candidateRepository.findById(99L)).thenReturn(Optional.of(candidate(99L)));
    when(applicationRepository.existsByJobIdAndCandidateId(5L, 99L)).thenReturn(false);

    ResumeEntity resume = new ResumeEntity();
    resume.setId(300L);
    resume.setStorageKey("resumes/300.pdf");
    MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", "application/pdf", "pdf".getBytes());
    when(resumeUploadService.uploadForApplication(file)).thenReturn(resume);
    when(transactionalExecutor.call(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    when(applicationRepository.saveAndFlush(any(ApplicationEntity.class))).thenAnswer(inv -> {
      ApplicationEntity app = inv.getArgument(0);
      app.setId(1001L);
      return app;
    });
    doAnswer(inv -> {
      ApplicationEntity app = inv.getArgument(0);
      app.setStatus(inv.getArgument(1));
      return null;
    }).when(stateMachine).transit(any(), any(), any(), any(), any());

    CandidateApplicationDTO dto = service.apply(5L, 99L, "张三", "13800138000", true, file);

    assertThat(dto.id()).isEqualTo(1001L);
    assertThat(dto.stage()).isEqualTo(CandidateStage.SCREENING);
    assertThat(dto.candidateLevel()).isEqualTo("mid");
    assertThat(dto.availableModes()).containsExactly(InterviewMode.TEXT, InterviewMode.VOICE);
    verify(stateMachine).initialize(any(), eq("候选人投递简历（自选定位：中级）"));
    verify(stateMachine).transit(any(), eq(ApplicationStatus.SCREENING), eq(OperatorType.SYSTEM), eq(null), any());
    verify(workflowService, never()).invite(any(), any(), any(), any());
  }

  @Test
  @DisplayName("候选人投递可自主选择初级/中级/高级定位，非法定位值返回 BAD_REQUEST")
  void applyStoresSelfSelectedCandidateLevelAndRejectsInvalidLevel() {
    MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", "application/pdf", "pdf".getBytes());

    assertThatThrownBy(() -> service.apply(5L, 99L, "王五", null, true, file, "principal"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.BAD_REQUEST.getCode());

    JobPositionEntity job = openJob(true, InterviewMode.TEXT);
    job.setDifficulty("mid");
    when(jobPositionRepository.findById(5L)).thenReturn(Optional.of(job));
    when(candidateRepository.findById(99L)).thenReturn(Optional.of(candidate(99L)));
    when(applicationRepository.existsByJobIdAndCandidateId(5L, 99L)).thenReturn(false);

    ResumeEntity resume = new ResumeEntity();
    resume.setId(305L);
    when(resumeUploadService.uploadForApplication(file)).thenReturn(resume);
    when(transactionalExecutor.call(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    when(applicationRepository.saveAndFlush(any(ApplicationEntity.class))).thenAnswer(inv -> {
      ApplicationEntity app = inv.getArgument(0);
      app.setId(1005L);
      return app;
    });

    CandidateApplicationDTO dto = service.apply(5L, 99L, "王五", null, true, file, "senior");
    assertThat(dto.candidateLevel()).isEqualTo("senior");
    verify(stateMachine).initialize(any(), eq("候选人投递简历（自选定位：高级）"));
  }

  @Test
  @DisplayName("岗位关闭 AI 初筛时，投递后直接邀请在线面试")
  void applyInvitesDirectlyWhenScreeningDisabled() {
    JobPositionEntity job = openJob(false, InterviewMode.TEXT);
    when(jobPositionRepository.findById(5L)).thenReturn(Optional.of(job));
    when(candidateRepository.findById(99L)).thenReturn(Optional.of(candidate(99L)));
    when(applicationRepository.existsByJobIdAndCandidateId(5L, 99L)).thenReturn(false);

    ResumeEntity resume = new ResumeEntity();
    resume.setId(301L);
    MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", "application/pdf", "pdf".getBytes());
    when(resumeUploadService.uploadForApplication(file)).thenReturn(resume);
    when(transactionalExecutor.call(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    when(applicationRepository.saveAndFlush(any(ApplicationEntity.class))).thenAnswer(inv -> {
      ApplicationEntity app = inv.getArgument(0);
      app.setId(1002L);
      return app;
    });
    doAnswer(inv -> {
      ApplicationEntity app = inv.getArgument(0);
      app.setStatus(ApplicationStatus.INTERVIEW_INVITED);
      return null;
    }).when(workflowService).invite(any(), eq(OperatorType.SYSTEM), eq(null), any());

    CandidateApplicationDTO dto = service.apply(5L, 99L, "李四", null, true, file);

    assertThat(dto.stage()).isEqualTo(CandidateStage.INTERVIEW_PENDING);
    assertThat(dto.availableModes()).isEqualTo(List.of(InterviewMode.TEXT));
    verify(workflowService).invite(any(), eq(OperatorType.SYSTEM), eq(null), any());
  }

  @Test
  @DisplayName("数据库唯一约束冲突时回滚清理已上传的简历对象与记录，并提示请勿重复投递")
  void cleansUpUploadedResumeOnDuplicateConstraintFailure() {
    JobPositionEntity job = openJob(true, InterviewMode.BOTH);
    when(jobPositionRepository.findById(5L)).thenReturn(Optional.of(job));
    when(candidateRepository.findById(99L)).thenReturn(Optional.of(candidate(99L)));
    when(applicationRepository.existsByJobIdAndCandidateId(5L, 99L)).thenReturn(false);

    ResumeEntity resume = new ResumeEntity();
    resume.setId(302L);
    resume.setStorageKey("resumes/302.pdf");
    MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", "application/pdf", "pdf".getBytes());
    when(resumeUploadService.uploadForApplication(file)).thenReturn(resume);
    when(transactionalExecutor.call(any())).thenThrow(new DataIntegrityViolationException("uk_job_candidate"));

    assertThatThrownBy(() -> service.apply(5L, 99L, "张三", "13800138000", true, file))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.APPLICATION_DUPLICATE.getCode());

    verify(resumeUploadService).deleteStoredQuietly("resumes/302.pdf");
    verify(resumeRepository).deleteById(302L);
  }

  @Test
  @DisplayName("候选人确认线下面试流转至 OFFLINE_CONFIRMED，申请改期标记 RESCHEDULE_REQUESTED 并保留原状态")
  void confirmsAndRequestsRescheduleForOfflineInterview() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(200L);
    app.setJobId(5L);
    app.setCandidateId(99L);
    app.setStatus(ApplicationStatus.OFFLINE_SCHEDULED);

    InterviewScheduleEntity schedule = new InterviewScheduleEntity();
    schedule.setId(70L);
    schedule.setApplicationId(200L);
    schedule.setCandidateConfirmStatus(CandidateApplicationService.CONFIRM_PENDING);

    when(applicationRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(app));
    when(scheduleRepository.findByApplicationId(200L)).thenReturn(Optional.of(schedule));
    when(jobPositionRepository.findById(5L)).thenReturn(Optional.of(openJob(true, InterviewMode.BOTH)));
    doAnswer(inv -> {
      ApplicationEntity targetApp = inv.getArgument(0);
      targetApp.setStatus(inv.getArgument(1));
      return null;
    }).when(stateMachine).transit(any(), any(), any(), any(), any());

    CandidateApplicationDTO confirmed = service.confirmOffline(99L, 200L);
    assertThat(confirmed.stage()).isEqualTo(CandidateStage.OFFLINE_CONFIRMED);
    assertThat(confirmed.offlineInterview().confirmStatus()).isEqualTo(CandidateApplicationService.CONFIRM_CONFIRMED);

    CandidateApplicationDTO rescheduled = service.requestReschedule(99L, 200L, "周三下午有毕业答辩，希望改到周五");
    assertThat(rescheduled.stage()).isEqualTo(CandidateStage.OFFLINE_CONFIRMED);
    assertThat(rescheduled.offlineInterview().confirmStatus()).isEqualTo(CandidateApplicationService.CONFIRM_RESCHEDULE);
    assertThat(rescheduled.offlineInterview().candidateNote()).isEqualTo("周三下午有毕业答辩，希望改到周五");
    verify(stateMachine).log(eq(app), eq(OperatorType.CANDIDATE), eq(99L), any());
  }
}
