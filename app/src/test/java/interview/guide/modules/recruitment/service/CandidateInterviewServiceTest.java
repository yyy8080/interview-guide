package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO.SessionStatus;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.service.JobPositionService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseInterviewService;
import interview.guide.modules.recruitment.model.AnswerTelemetry;
import interview.guide.modules.recruitment.model.AnswerTelemetryEntity;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateAnswerRequest;
import interview.guide.modules.recruitment.model.CandidateAnswerResponse;
import interview.guide.modules.recruitment.model.CandidateInterviewDTO;
import interview.guide.modules.recruitment.model.CandidateQuestionDTO;
import interview.guide.modules.recruitment.model.OperatorType;
import interview.guide.modules.recruitment.repository.AnswerTelemetryRepository;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.ProctorEventRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import java.lang.reflect.RecordComponent;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("候选人在线面试服务测试")
class CandidateInterviewServiceTest {

  @Mock
  private ApplicationRepository applicationRepository;

  @Mock
  private ProctorEventRepository proctorEventRepository;

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private AnswerTelemetryRepository answerTelemetryRepository;

  @Mock
  private interview.guide.modules.recruitment.repository.WrittenTestRepository writtenTestRepository;

  @Mock
  private JobPositionService jobPositionService;

  @Mock
  private InterviewSessionService interviewSessionService;

  @Mock
  private KnowledgeBaseInterviewService knowledgeBaseInterviewService;

  @Mock
  private VoiceInterviewService voiceInterviewService;

  @Mock
  private ApplicationStateMachine stateMachine;

  @Mock
  private ApplicationWorkflowService workflowService;

  @Mock
  private CandidateAuthService candidateAuthService;

  @Mock
  private TransactionalExecutor transactionalExecutor;

  @Mock
  private RedisService redisService;

  private CandidateInterviewService service;

  @BeforeEach
  void setUp() {
    service = new CandidateInterviewService(
        applicationRepository, proctorEventRepository, answerTelemetryRepository, writtenTestRepository,
        resumeRepository, jobPositionService, interviewSessionService, knowledgeBaseInterviewService,
        voiceInterviewService, stateMachine, workflowService, candidateAuthService, transactionalExecutor,
        redisService, new RecruitmentProperties());
  }

  @Test
  @DisplayName("候选人题目脱敏严格剔除参考答案、关键点、评分标准与得分反馈")
  void sanitizesInterviewQuestionStrictly() {
    InterviewQuestionDTO full = InterviewQuestionDTO.fromQuestionBank(
        0, "请解释 MySQL MVCC 原理", "MYSQL", "MySQL", "MVCC",
        "隐藏列 + Undo Log + ReadView", List.of("trx_id", "ReadView"), "答出三要素满分", "kb-doc-1"
    ).withEvaluation(92, "回答准确");

    CandidateQuestionDTO sanitized = CandidateInterviewService.sanitize(full);

    assertThat(sanitized.questionIndex()).isZero();
    assertThat(sanitized.question()).isEqualTo("请解释 MySQL MVCC 原理");
    assertThat(sanitized.category()).isEqualTo("MySQL");
    assertThat(sanitized.followUp()).isFalse();

    List<String> componentNames = Arrays.stream(CandidateQuestionDTO.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
    assertThat(componentNames)
        .containsExactly("questionIndex", "question", "category", "followUp")
        .doesNotContain("referenceAnswer", "keyPoints", "scoringRubric", "score", "feedback");
  }

  @Test
  @DisplayName("开始面试拒绝 BOTH 作为具体选择，并在岗位模式不匹配时返回 INTERVIEW_MODE_NOT_ALLOWED")
  void validatesConcreteModeAndJobAllowedMode() {
    assertThatThrownBy(() -> service.start(10L, 100L, InterviewMode.BOTH))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.BAD_REQUEST.getCode());

    ApplicationEntity invited = new ApplicationEntity();
    invited.setId(100L);
    invited.setCandidateId(10L);
    invited.setJobId(7L);
    invited.setStatus(ApplicationStatus.INTERVIEW_INVITED);
    invited.setInviteExpiresAt(LocalDateTime.now().plusDays(3));

    JobPositionEntity textOnlyJob = new JobPositionEntity();
    textOnlyJob.setId(7L);
    textOnlyJob.setInterviewMode(InterviewMode.TEXT);

    when(applicationRepository.findByIdAndCandidateId(100L, 10L)).thenReturn(Optional.of(invited));
    when(redisService.executeWithLock(any(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(inv -> ((RedisService.LockedOperation<?>) inv.getArgument(4)).execute());
    when(jobPositionService.getEntity(7L)).thenReturn(textOnlyJob);

    assertThatThrownBy(() -> service.start(10L, 100L, InterviewMode.VOICE))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.INTERVIEW_MODE_NOT_ALLOWED.getCode());
  }

  @Test
  @DisplayName("岗位配置为 BOTH 时，候选人可自主选择语音面试并返回候选人专属 WebSocket 路径")
  void allowsCandidateChoiceWhenJobModeIsBoth() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(101L);
    app.setCandidateId(10L);
    app.setJobId(8L);
    app.setResumeId(50L);
    app.setStatus(ApplicationStatus.INTERVIEW_INVITED);
    app.setInviteExpiresAt(LocalDateTime.now().plusDays(3));

    JobPositionEntity bothJob = new JobPositionEntity();
    bothJob.setId(8L);
    bothJob.setTitle("全栈工程师");
    bothJob.setInterviewMode(InterviewMode.BOTH);
    bothJob.setTimeLimitMinutes(25);

    when(applicationRepository.findByIdAndCandidateId(101L, 10L)).thenReturn(Optional.of(app));
    when(applicationRepository.findByIdForUpdate(101L)).thenReturn(Optional.of(app));
    when(jobPositionService.getEntity(8L)).thenReturn(bothJob);
    when(redisService.executeWithLock(any(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(inv -> ((RedisService.LockedOperation<?>) inv.getArgument(4)).execute());
    doAnswer(inv -> {
      ((Runnable) inv.getArgument(0)).run();
      return null;
    }).when(transactionalExecutor).run(any());

    SessionResponseDTO voiceResp = new SessionResponseDTO();
    voiceResp.setSessionId(888L);
    when(voiceInterviewService.createSession(any())).thenReturn(voiceResp);
    doAnswer(inv -> {
      ApplicationEntity target = inv.getArgument(0);
      target.setStatus(inv.getArgument(1));
      target.setInterviewStartedAt(LocalDateTime.now());
      return null;
    }).when(stateMachine).transit(any(), any(), any(), any(), any());

    CandidateInterviewDTO dto = service.start(10L, 101L, InterviewMode.VOICE, "senior");

    assertThat(dto.mode()).isEqualTo(InterviewMode.VOICE);
    assertThat(dto.candidateLevel()).isEqualTo("senior");
    assertThat(app.getCandidateLevel()).isEqualTo("senior");
    assertThat(dto.voiceSessionId()).isEqualTo(888L);
    assertThat(dto.voiceWsPath()).isEqualTo("/ws/candidate/voice-interview/888");
    ArgumentCaptor<interview.guide.modules.voiceinterview.dto.CreateSessionRequest> reqCaptor =
        ArgumentCaptor.forClass(interview.guide.modules.voiceinterview.dto.CreateSessionRequest.class);
    verify(voiceInterviewService).createSession(reqCaptor.capture());
    assertThat(reqCaptor.getValue().getDifficulty()).isEqualTo("senior");
  }

  @Test
  @DisplayName("文字面试按候选人自选定位出题；绑定题库且所选定位题目不足时平滑降级至岗位默认难度")
  void appliesCandidateLevelToTextInterviewAndFallsBackWhenKbInsufficient() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(106L);
    app.setCandidateId(10L);
    app.setJobId(11L);
    app.setResumeId(51L);
    app.setCandidateLevel("mid");
    app.setStatus(ApplicationStatus.INTERVIEW_INVITED);
    app.setInviteExpiresAt(LocalDateTime.now().plusDays(2));

    JobPositionEntity kbJob = new JobPositionEntity();
    kbJob.setId(11L);
    kbJob.setTitle("后端研发");
    kbJob.setDifficulty("mid");
    kbJob.setInterviewMode(InterviewMode.TEXT);
    kbJob.setKnowledgeBaseId(99L);
    kbJob.setQuestionCount(5);
    kbJob.setTimeLimitMinutes(30);

    interview.guide.modules.resume.model.ResumeEntity resume = new interview.guide.modules.resume.model.ResumeEntity();
    resume.setId(51L);
    resume.setResumeText("熟悉 Spring Boot 与 Redis");

    InterviewQuestionDTO q0 = InterviewQuestionDTO.create(0, "请介绍 Redis 持久化机制", "TECH", "Redis");
    InterviewSessionDTO sessionDTO = new InterviewSessionDTO(
        "sess-106", "熟悉 Spring Boot 与 Redis", 5, 0, List.of(q0), SessionStatus.IN_PROGRESS, 51L, "mid");

    when(applicationRepository.findByIdAndCandidateId(106L, 10L)).thenReturn(Optional.of(app));
    when(applicationRepository.findByIdForUpdate(106L)).thenReturn(Optional.of(app));
    when(jobPositionService.getEntity(11L)).thenReturn(kbJob);
    when(redisService.executeWithLock(any(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(inv -> ((RedisService.LockedOperation<?>) inv.getArgument(4)).execute());
    doAnswer(inv -> {
      ((Runnable) inv.getArgument(0)).run();
      return null;
    }).when(transactionalExecutor).run(any());
    when(knowledgeBaseInterviewService.createSession(
        new interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest(99L, null, "junior", 5, 0, null)))
        .thenThrow(new BusinessException(ErrorCode.INTERVIEW_QUESTION_INSUFFICIENT));
    when(knowledgeBaseInterviewService.createSession(
        new interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest(99L, null, "mid", 5, 0, null)))
        .thenReturn(sessionDTO);
    when(interviewSessionService.getSession("sess-106")).thenReturn(sessionDTO);
    when(interviewSessionService.getCurrentQuestion("sess-106")).thenReturn(q0);
    doAnswer(inv -> {
      ApplicationEntity target = inv.getArgument(0);
      target.setStatus(inv.getArgument(1));
      target.setInterviewStartedAt(LocalDateTime.now());
      return null;
    }).when(stateMachine).transit(any(), any(), any(), any(), any());

    CandidateInterviewDTO dto = service.start(10L, 106L, InterviewMode.TEXT, "junior");
    assertThat(dto.candidateLevel()).isEqualTo("junior");
    assertThat(dto.currentQuestion()).isNotNull();
    assertThat(dto.currentQuestion().question()).isEqualTo("请介绍 Redis 持久化机制");
  }

  @Test
  @DisplayName("文字面试回答最后一题后自动标记面试完成并触发 AI 评估流")
  void marksFinishedWhenLastTextQuestionAnswered() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(102L);
    app.setCandidateId(10L);
    app.setStatus(ApplicationStatus.INTERVIEWING);
    app.setInterviewMode(InterviewMode.TEXT);
    app.setInterviewSessionId("sess-102");
    app.setInterviewDeadlineAt(LocalDateTime.now().plusMinutes(20));

    when(applicationRepository.findByIdAndCandidateId(102L, 10L)).thenReturn(Optional.of(app));
    when(interviewSessionService.submitAnswer(any()))
        .thenReturn(new SubmitAnswerResponse(false, null, 5, 5));

    CandidateAnswerResponse response = service.answer(10L, 102L, new CandidateAnswerRequest(4, "使用唯一索引幂等"));

    assertThat(response.finished()).isTrue();
    assertThat(response.nextQuestion()).isNull();
    verify(workflowService).markInterviewFinished(102L, OperatorType.CANDIDATE, 10L, "候选人完成全部题目");
  }

  @Test
  @DisplayName("提交答案时随附的作答遥测按题保存（含答案字数），遥测保存失败不影响作答")
  void savesAnswerTelemetryWithAnswer() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(103L);
    app.setCandidateId(10L);
    app.setStatus(ApplicationStatus.INTERVIEWING);
    app.setInterviewMode(InterviewMode.TEXT);
    app.setInterviewSessionId("sess-103");
    app.setInterviewDeadlineAt(LocalDateTime.now().plusMinutes(20));

    when(applicationRepository.findByIdAndCandidateId(103L, 10L)).thenReturn(Optional.of(app));
    when(interviewSessionService.submitAnswer(any()))
        .thenReturn(new SubmitAnswerResponse(true, null, 2, 5));
    when(answerTelemetryRepository.findByApplicationIdAndQuestionIndex(103L, 1)).thenReturn(Optional.empty());

    AnswerTelemetry telemetry = new AnswerTelemetry(90_000L, 4_000L, 20, 180, 0, 35_000L);
    CandidateAnswerResponse response =
        service.answer(10L, 103L, new CandidateAnswerRequest(1, "  幂等键 + 唯一索引  ", telemetry));

    assertThat(response.finished()).isFalse();
    ArgumentCaptor<AnswerTelemetryEntity> captor = ArgumentCaptor.forClass(AnswerTelemetryEntity.class);
    verify(answerTelemetryRepository).save(captor.capture());
    AnswerTelemetryEntity saved = captor.getValue();
    assertThat(saved.getApplicationId()).isEqualTo(103L);
    assertThat(saved.getQuestionIndex()).isEqualTo(1);
    assertThat(saved.getPastedChars()).isEqualTo(180);
    assertThat(saved.getAwayMs()).isEqualTo(35_000L);
    assertThat(saved.getAnswerChars()).isEqualTo("幂等键 + 唯一索引".length());

    when(answerTelemetryRepository.findByApplicationIdAndQuestionIndex(103L, 2))
        .thenThrow(new IllegalStateException("db down"));
    CandidateAnswerResponse second =
        service.answer(10L, 103L, new CandidateAnswerRequest(2, "答案", telemetry));
    assertThat(second.finished()).isFalse();
  }

  @Test
  @DisplayName("语音 WebSocket 握手严格校验令牌归属与 INTERVIEWING 状态")
  void verifiesVoiceHandshakeOwnershipAndStatus() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(103L);
    app.setCandidateId(10L);
    app.setVoiceSessionId(900L);
    app.setStatus(ApplicationStatus.INTERVIEWING);

    when(candidateAuthService.requireCandidateId("valid-token")).thenReturn(10L);
    when(candidateAuthService.requireCandidateId("other-token")).thenReturn(99L);
    when(applicationRepository.findByVoiceSessionId(900L)).thenReturn(Optional.of(app));

    assertThat(service.canAccessVoice("valid-token", 900L)).isTrue();
    assertThat(service.canAccessVoice("other-token", 900L)).isFalse();
    assertThat(service.canAccessVoice("", 900L)).isFalse();

    app.setStatus(ApplicationStatus.AI_REVIEWING);
    assertThat(service.canAccessVoice("valid-token", 900L)).isFalse();
  }

  @Test
  @DisplayName("岗位启用在线笔试时，笔试未完成前禁止开始面试；笔试作答期间正常记录防作弊事件")
  void gatesInterviewUntilWrittenCompletedAndRecordsProctorDuringWritten() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(105L);
    app.setCandidateId(10L);
    app.setJobId(9L);
    app.setStatus(ApplicationStatus.INTERVIEW_INVITED);
    app.setInviteExpiresAt(LocalDateTime.now().plusDays(2));

    JobPositionEntity job = new JobPositionEntity();
    job.setId(9L);
    job.setInterviewMode(InterviewMode.TEXT);
    job.setWrittenTestEnabled(true);

    when(applicationRepository.findByIdAndCandidateId(105L, 10L)).thenReturn(Optional.of(app));
    when(redisService.executeWithLock(any(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(inv -> ((RedisService.LockedOperation<?>) inv.getArgument(4)).execute());
    when(jobPositionService.getEntity(9L)).thenReturn(job);
    when(writtenTestRepository.findByApplicationId(105L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.start(10L, 105L, InterviewMode.TEXT))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.INTERVIEW_NOT_AVAILABLE.getCode());

    var inProgress = new interview.guide.modules.recruitment.model.WrittenTestEntity();
    inProgress.setApplicationId(105L);
    inProgress.setStatus(interview.guide.modules.recruitment.model.WrittenTestStatus.IN_PROGRESS);
    when(writtenTestRepository.findByApplicationId(105L)).thenReturn(Optional.of(inProgress));

    assertThatThrownBy(() -> service.start(10L, 105L, InterviewMode.TEXT))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("笔试");

    when(writtenTestRepository.existsByApplicationIdAndStatus(
        105L, interview.guide.modules.recruitment.model.WrittenTestStatus.IN_PROGRESS)).thenReturn(true);
    service.recordProctor(10L, 105L, new interview.guide.modules.recruitment.model.ProctorEventRequest(
        interview.guide.modules.recruitment.model.ProctorEventType.TAB_HIDDEN, "笔试", 5_000L, 2, null));
    verify(proctorEventRepository).save(any());
  }
}
