package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.RecruitmentProperties;
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
import tools.jackson.databind.ObjectMapper;

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

  @Mock
  private RecruitmentInvitationService invitationService;

  private final ObjectMapper objectMapper = new ObjectMapper();

  private CandidateApplicationService service;

  @BeforeEach
  void setUp() {
    service = new CandidateApplicationService(
        applicationRepository, candidateRepository, jobPositionRepository, scheduleRepository,
        resumeUploadService, resumeRepository, stateMachine, workflowService, transactionalExecutor,
        objectMapper, new RecruitmentProperties(), invitationService);
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

  @Test
  @DisplayName("简历初筛未通过时向候选人返回脱敏原因与差距项；AI 面试或 HR 未通过不暴露内部评估细节")
  void exposesScreeningRejectFeedbackOnlyForScreenRejectedApplications() {
    ApplicationEntity screenRejected = new ApplicationEntity();
    screenRejected.setId(301L);
    screenRejected.setJobId(5L);
    screenRejected.setCandidateId(99L);
    screenRejected.setStatus(ApplicationStatus.SCREEN_REJECTED);
    screenRejected.setScreeningScore(35);
    screenRejected.setScreeningResultJson("""
        {
          "score": 35,
          "summary": "简历主要集中在平面设计方向，暂未体现 Java 后端开发与数据库实践经历。",
          "strengths": ["作品集完整"],
          "risks": ["技术栈方向不符"],
          "matchedRequirements": [],
          "missingRequirements": ["Java 与 Spring Boot 开发经验", "关系型数据库使用经验"]
        }
        """);

    ApplicationEntity aiRejected = new ApplicationEntity();
    aiRejected.setId(302L);
    aiRejected.setJobId(5L);
    aiRejected.setCandidateId(99L);
    aiRejected.setStatus(ApplicationStatus.AI_REJECTED);
    aiRejected.setScreeningResultJson("""
        {"score": 78, "summary": "初筛已通过", "missingRequirements": []}
        """);

    ApplicationEntity hrRejected = new ApplicationEntity();
    hrRejected.setId(303L);
    hrRejected.setJobId(5L);
    hrRejected.setCandidateId(99L);
    hrRejected.setStatus(ApplicationStatus.HR_REJECTED);
    hrRejected.setHrComment("内部评语：薪资预期超出预算");

    when(applicationRepository.findByCandidateIdOrderBySubmittedAtDesc(99L))
        .thenReturn(List.of(screenRejected, aiRejected, hrRejected));
    when(jobPositionRepository.findAllById(List.of(5L)))
        .thenReturn(List.of(openJob(true, InterviewMode.BOTH)));

    List<CandidateApplicationDTO> list = service.listMine(99L);
    assertThat(list).hasSize(3);

    CandidateApplicationDTO dtoScreen = list.get(0);
    assertThat(dtoScreen.stage()).isEqualTo(CandidateStage.REJECTED);
    assertThat(dtoScreen.screeningFeedback()).isNotNull();
    assertThat(dtoScreen.screeningFeedback().summary())
        .isEqualTo("简历主要集中在平面设计方向，暂未体现 Java 后端开发与数据库实践经历。");
    assertThat(dtoScreen.screeningFeedback().missingRequirements())
        .containsExactly("Java 与 Spring Boot 开发经验", "关系型数据库使用经验");
    assertThat(dtoScreen.screeningFeedback().canResubmit()).isTrue();
    assertThat(dtoScreen.screeningFeedback().revisionCount()).isZero();
    assertThat(dtoScreen.screeningFeedback().maxRevisions()).isEqualTo(1);

    assertThat(list.get(1).stage()).isEqualTo(CandidateStage.REJECTED);
    assertThat(list.get(1).screeningFeedback()).isNull();

    assertThat(list.get(2).stage()).isEqualTo(CandidateStage.REJECTED);
    assertThat(list.get(2).screeningFeedback()).isNull();
  }

  @Test
  @DisplayName("初筛未通过允许修改并重新提交 1 次简历回到 SCREENING，第 2 次重提返回 APPLICATION_STATUS_INVALID")
  void resubmitsResumeOnceWhenScreenRejectedAndBlocksSecondAttempt() {
    JobPositionEntity job = openJob(true, InterviewMode.BOTH);
    ApplicationEntity app = new ApplicationEntity();
    app.setId(401L);
    app.setJobId(5L);
    app.setCandidateId(99L);
    app.setResumeId(10L);
    app.setCandidateLevel("mid");
    app.setStatus(ApplicationStatus.SCREEN_REJECTED);
    app.setResumeRevisionCount(0);
    app.setScreeningScore(52);
    app.setScreeningResultJson("{\"score\":52,\"summary\":\"缺少后端实践描述\"}");

    ResumeEntity revisedResume = new ResumeEntity();
    revisedResume.setId(410L);
    revisedResume.setStorageKey("resumes/410.pdf");
    MockMultipartFile file = new MockMultipartFile("file", "cv-v2.pdf", "application/pdf", "pdf2".getBytes());

    when(applicationRepository.findByIdAndCandidateId(401L, 99L)).thenReturn(Optional.of(app));
    when(applicationRepository.findByIdForUpdate(401L)).thenReturn(Optional.of(app));
    when(jobPositionRepository.findById(5L)).thenReturn(Optional.of(job));
    when(resumeUploadService.uploadForApplication(file)).thenReturn(revisedResume);
    when(transactionalExecutor.call(any())).thenAnswer(inv -> ((Supplier<?>) inv.getArgument(0)).get());
    doAnswer(inv -> {
      ApplicationEntity target = inv.getArgument(0);
      target.setStatus(inv.getArgument(1));
      return null;
    }).when(stateMachine).transit(any(), any(), any(), any(), any());

    CandidateApplicationDTO updated = service.resubmitResume(99L, 401L, file, "junior");
    assertThat(updated.stage()).isEqualTo(CandidateStage.SCREENING);
    assertThat(updated.candidateLevel()).isEqualTo("junior");
    assertThat(app.getResumeId()).isEqualTo(410L);
    assertThat(app.getResumeRevisionCount()).isEqualTo(1);
    assertThat(app.getScreeningScore()).isNull();
    assertThat(app.getScreeningResultJson()).isNull();
    verify(stateMachine).transit(eq(app), eq(ApplicationStatus.SCREENING), eq(OperatorType.CANDIDATE), eq(99L), any());

    // 假设修改后的简历第 2 次初筛仍未通过，此时 resumeRevisionCount == 1，不允许再次重提
    app.setStatus(ApplicationStatus.SCREEN_REJECTED);
    app.setScreeningResultJson("{\"score\":55,\"summary\":\"仍未达到岗位要求\"}");
    CandidateApplicationDTO mineAfterSecondReject = service.getMine(99L, 401L);
    assertThat(mineAfterSecondReject.screeningFeedback()).isNotNull();
    assertThat(mineAfterSecondReject.screeningFeedback().canResubmit()).isFalse();
    assertThat(mineAfterSecondReject.screeningFeedback().revisionCount()).isEqualTo(1);

    MockMultipartFile thirdFile = new MockMultipartFile("file", "cv-v3.pdf", "application/pdf", "pdf3".getBytes());
    assertThatThrownBy(() -> service.resubmitResume(99L, 401L, thirdFile, null))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.APPLICATION_STATUS_INVALID.getCode());
    verify(resumeUploadService, never()).uploadForApplication(thirdFile);
  }

  @Test
  @DisplayName("非本岗位受邀邮箱投递简历时直接拒绝且不上传文件")
  void blocksApplyWhenNotInvitedToJob() {
    JobPositionEntity job = openJob(true, InterviewMode.BOTH);
    when(jobPositionRepository.findById(5L)).thenReturn(Optional.of(job));
    when(candidateRepository.findById(99L)).thenReturn(Optional.of(candidate(99L)));
    when(invitationService.assertCanApplyAndGet(5L, 99L, true))
        .thenThrow(new BusinessException(ErrorCode.CANDIDATE_NOT_INVITED, "非受邀邮箱不允许投递简历面试"));

    MockMultipartFile file = new MockMultipartFile("file", "cv.pdf", "application/pdf", "pdf".getBytes());
    assertThatThrownBy(() -> service.apply(5L, 99L, "张三", "13800138000", true, file, "mid"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.CANDIDATE_NOT_INVITED.getCode());
    verify(resumeUploadService, never()).uploadForApplication(any());
  }
}
