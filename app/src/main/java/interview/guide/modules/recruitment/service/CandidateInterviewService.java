package interview.guide.modules.recruitment.service;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.interview.model.CreateInterviewRequest;
import interview.guide.modules.interview.model.InterviewQuestionDTO;
import interview.guide.modules.interview.model.InterviewSessionDTO;
import interview.guide.modules.interview.model.SubmitAnswerRequest;
import interview.guide.modules.interview.model.SubmitAnswerResponse;
import interview.guide.modules.interview.service.InterviewSessionService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.service.JobPositionService;
import interview.guide.modules.knowledgebase.model.CreateKnowledgeBaseInterviewRequest;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseInterviewService;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateAnswerRequest;
import interview.guide.modules.recruitment.model.CandidateAnswerResponse;
import interview.guide.modules.recruitment.model.CandidateInterviewDTO;
import interview.guide.modules.recruitment.model.CandidateQuestionDTO;
import interview.guide.modules.recruitment.model.OperatorType;
import interview.guide.modules.recruitment.model.ProctorEventEntity;
import interview.guide.modules.recruitment.model.ProctorEventRequest;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.ProctorEventRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.dto.SessionResponseDTO;
import interview.guide.modules.voiceinterview.service.VoiceInterviewService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 候选人在线面试：开始面试（创建文字 / 语音会话）、答题、交卷、语音结束、防作弊事件。
 * <p>
 * 复用现有文字面试与语音面试引擎；对候选人只暴露题干，不返回参考答案与评分要点。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateInterviewService {

  /** 候选人语音面试 WebSocket 路径前缀（握手时校验候选人令牌） */
  public static final String CANDIDATE_VOICE_WS_PREFIX = "/ws/candidate/voice-interview/";

  /** 岗位未设置时长时文字面试的兜底截止时长，避免放弃作答的投递长期停留在面试中 */
  static final Duration TEXT_HARD_LIMIT = Duration.ofHours(24);

  /** 语音面试在计划时长基础上的宽限 */
  static final Duration VOICE_GRACE = Duration.ofMinutes(5);

  /** 提交答案时对截止时间的宽限（网络延迟） */
  static final Duration ANSWER_GRACE = Duration.ofSeconds(30);

  /** 单个投递最多记录的防作弊事件数 */
  static final int MAX_PROCTOR_EVENTS = 500;

  private static final int DEFAULT_QUESTION_COUNT = 8;

  private final ApplicationRepository applicationRepository;
  private final ProctorEventRepository proctorEventRepository;
  private final ResumeRepository resumeRepository;
  private final JobPositionService jobPositionService;
  private final InterviewSessionService interviewSessionService;
  private final KnowledgeBaseInterviewService knowledgeBaseInterviewService;
  private final VoiceInterviewService voiceInterviewService;
  private final ApplicationStateMachine stateMachine;
  private final ApplicationWorkflowService workflowService;
  private final CandidateAuthService candidateAuthService;
  private final TransactionalExecutor transactionalExecutor;
  private final RedisService redisService;
  private final RecruitmentProperties properties;

  // ========== 开始面试 ==========

  /**
   * 开始在线面试：校验邀请 → 事务外创建面试会话（含 LLM 出题）→ 事务内写回会话并进入面试中
   */
  public CandidateInterviewDTO start(Long candidateId, Long applicationId, InterviewMode mode) {
    if (mode == null || mode == InterviewMode.BOTH) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请选择文字面试或语音面试");
    }
    ApplicationEntity current = requireOwned(candidateId, applicationId);
    if (current.getStatus() == ApplicationStatus.INTERVIEWING) {
      return getState(candidateId, applicationId);
    }
    String lockKey = "recruitment:interview:start:" + applicationId;
    try {
      redisService.executeWithLock(lockKey, 1, 300, TimeUnit.SECONDS,
          () -> doStart(candidateId, applicationId, mode));
    } catch (BusinessException e) {
      if (ErrorCode.INTERNAL_ERROR.getCode().equals(e.getCode()) && e.getMessage() != null
          && e.getMessage().startsWith("获取锁")) {
        throw new BusinessException(ErrorCode.INTERVIEW_NOT_AVAILABLE, "面试正在准备中，请稍后刷新");
      }
      throw e;
    }
    return getState(candidateId, applicationId);
  }

  private Void doStart(Long candidateId, Long applicationId, InterviewMode mode) {
    ApplicationEntity application = requireOwned(candidateId, applicationId);
    if (application.getStatus() == ApplicationStatus.INTERVIEWING) {
      return null;
    }
    assertStartable(application);
    JobPositionEntity job = jobPositionService.getEntity(application.getJobId());
    if (job.getInterviewMode() == null || !job.getInterviewMode().allows(mode)) {
      throw new BusinessException(ErrorCode.INTERVIEW_MODE_NOT_ALLOWED);
    }

    LocalDateTime now = LocalDateTime.now();
    String textSessionId = null;
    Long voiceSessionId = null;
    LocalDateTime deadline;
    if (mode == InterviewMode.TEXT) {
      textSessionId = createTextSession(application, job);
      deadline = job.getTimeLimitMinutes() != null && job.getTimeLimitMinutes() > 0
          ? now.plusMinutes(job.getTimeLimitMinutes())
          : now.plus(TEXT_HARD_LIMIT);
    } else {
      int planned = plannedVoiceMinutes(job);
      voiceSessionId = createVoiceSession(application, job, planned);
      deadline = now.plusMinutes(planned).plus(VOICE_GRACE);
    }

    String finalTextSessionId = textSessionId;
    Long finalVoiceSessionId = voiceSessionId;
    transactionalExecutor.run(() -> {
      ApplicationEntity locked = applicationRepository.findByIdForUpdate(applicationId)
          .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
      assertStartable(locked);
      locked.setInterviewMode(mode);
      locked.setInterviewSessionId(finalTextSessionId);
      locked.setVoiceSessionId(finalVoiceSessionId);
      locked.setInterviewDeadlineAt(deadline);
      stateMachine.transit(locked, ApplicationStatus.INTERVIEWING, OperatorType.CANDIDATE, candidateId,
          "候选人开始" + (mode == InterviewMode.TEXT ? "文字" : "语音") + "面试");
    });
    log.info("候选人开始在线面试: applicationId={}, mode={}", applicationId, mode);
    return null;
  }

  private void assertStartable(ApplicationEntity application) {
    if (application.getStatus() != ApplicationStatus.INTERVIEW_INVITED) {
      throw new BusinessException(ErrorCode.INTERVIEW_NOT_AVAILABLE);
    }
    if (application.getInviteExpiresAt() != null && application.getInviteExpiresAt().isBefore(LocalDateTime.now())) {
      throw new BusinessException(ErrorCode.INTERVIEW_NOT_AVAILABLE, "面试邀请已过期");
    }
  }

  private String createTextSession(ApplicationEntity application, JobPositionEntity job) {
    int questionCount = clamp(job.getQuestionCount() != null ? job.getQuestionCount() : DEFAULT_QUESTION_COUNT,
        3, 20);
    InterviewSessionDTO session;
    if (job.getKnowledgeBaseId() != null) {
      session = knowledgeBaseInterviewService.createSession(new CreateKnowledgeBaseInterviewRequest(
          job.getKnowledgeBaseId(), null, job.getDifficulty(), questionCount, 0, job.getLlmProvider()));
    } else {
      ResumeEntity resume = resumeRepository.findById(application.getResumeId()).orElse(null);
      boolean custom = InterviewSkillService.CUSTOM_SKILL_ID.equals(job.getSkillId());
      session = interviewSessionService.createSession(new CreateInterviewRequest(
          resume != null ? resume.getResumeText() : null,
          questionCount,
          resume != null ? resume.getId() : null,
          Boolean.TRUE,
          job.getLlmProvider(),
          job.getSkillId(),
          job.getDifficulty(),
          custom ? jobPositionService.readCategories(job) : null,
          custom ? job.getJdText() : null,
          "application-" + application.getId()));
    }
    return session.sessionId();
  }

  private Long createVoiceSession(ApplicationEntity application, JobPositionEntity job, int plannedMinutes) {
    SessionResponseDTO response = voiceInterviewService.createSession(CreateSessionRequest.builder()
        .skillId(job.getSkillId())
        .difficulty(job.getDifficulty())
        .customJdText(job.getJdText())
        .resumeId(application.getResumeId())
        .introEnabled(true)
        .techEnabled(true)
        .projectEnabled(true)
        .hrEnabled(true)
        .plannedDuration(plannedMinutes)
        .llmProvider(job.getLlmProvider())
        .build());
    return response.getSessionId();
  }

  private int plannedVoiceMinutes(JobPositionEntity job) {
    Integer limit = job.getTimeLimitMinutes();
    int minutes = limit != null && limit > 0 ? limit : properties.getDefaultVoiceDurationMinutes();
    return clamp(minutes, 5, 120);
  }

  // ========== 面试状态 ==========

  public CandidateInterviewDTO getState(Long candidateId, Long applicationId) {
    ApplicationEntity application = requireOwned(candidateId, applicationId);
    JobPositionEntity job = jobPositionService.getEntity(application.getJobId());
    boolean interviewing = application.getStatus() == ApplicationStatus.INTERVIEWING;
    Long remaining = null;
    if (interviewing && application.getInterviewDeadlineAt() != null) {
      remaining = Math.max(0, Duration.between(LocalDateTime.now(), application.getInterviewDeadlineAt()).toSeconds());
    }

    int total = 0;
    int currentIndex = 0;
    CandidateQuestionDTO currentQuestion = null;
    boolean finished = !interviewing && application.getInterviewStartedAt() != null;
    if (application.getInterviewMode() == InterviewMode.TEXT && application.getInterviewSessionId() != null) {
      InterviewSessionDTO session = interviewSessionService.getSession(application.getInterviewSessionId());
      total = session.totalQuestions();
      currentIndex = session.currentQuestionIndex();
      if (interviewing) {
        InterviewQuestionDTO question = interviewSessionService.getCurrentQuestion(application.getInterviewSessionId());
        currentQuestion = sanitize(question);
        finished = question == null;
      }
    }

    boolean voice = application.getInterviewMode() == InterviewMode.VOICE && application.getVoiceSessionId() != null;
    return new CandidateInterviewDTO(
        application.getId(),
        job.getTitle(),
        application.getInterviewMode(),
        application.getStatus().toCandidateStage(),
        application.getInterviewStartedAt(),
        application.getInterviewDeadlineAt(),
        remaining,
        total,
        currentIndex,
        currentQuestion,
        finished,
        voice ? application.getVoiceSessionId() : null,
        voice && interviewing ? CANDIDATE_VOICE_WS_PREFIX + application.getVoiceSessionId() : null,
        application.getInterviewMode() == InterviewMode.VOICE ? plannedVoiceMinutes(job) : null);
  }

  /** 去掉参考答案、评分要点等信息，只保留题干 */
  static CandidateQuestionDTO sanitize(InterviewQuestionDTO question) {
    if (question == null) {
      return null;
    }
    return new CandidateQuestionDTO(question.questionIndex(), question.question(), question.category(),
        question.isFollowUp());
  }

  // ========== 文字面试作答 ==========

  public CandidateAnswerResponse answer(Long candidateId, Long applicationId, CandidateAnswerRequest request) {
    ApplicationEntity application = requireInterviewing(candidateId, applicationId, InterviewMode.TEXT);
    if (application.getInterviewDeadlineAt() != null
        && LocalDateTime.now().isAfter(application.getInterviewDeadlineAt().plus(ANSWER_GRACE))) {
      autoFinishOverdue(applicationId);
      throw new BusinessException(ErrorCode.INTERVIEW_NOT_AVAILABLE, "作答时间已结束，系统已自动交卷");
    }
    String answer = request.answer() == null || request.answer().isBlank() ? "（未作答）" : request.answer().trim();
    SubmitAnswerResponse response = interviewSessionService.submitAnswer(
        new SubmitAnswerRequest(application.getInterviewSessionId(), request.questionIndex(), answer));
    if (!response.hasNextQuestion()) {
      workflowService.markInterviewFinished(applicationId, OperatorType.CANDIDATE, candidateId, "候选人完成全部题目");
    }
    return new CandidateAnswerResponse(!response.hasNextQuestion(), sanitize(response.nextQuestion()),
        response.currentIndex(), response.totalQuestions());
  }

  /** 提前交卷 */
  public CandidateInterviewDTO complete(Long candidateId, Long applicationId) {
    ApplicationEntity application = requireInterviewing(candidateId, applicationId, InterviewMode.TEXT);
    completeTextQuietly(application.getInterviewSessionId());
    workflowService.markInterviewFinished(applicationId, OperatorType.CANDIDATE, candidateId, "候选人提前交卷");
    return getState(candidateId, applicationId);
  }

  // ========== 语音面试 ==========

  /** 候选人结束语音面试 */
  public CandidateInterviewDTO endVoice(Long candidateId, Long applicationId) {
    ApplicationEntity application = requireInterviewing(candidateId, applicationId, InterviewMode.VOICE);
    voiceInterviewService.endSessionIfInProgress(String.valueOf(application.getVoiceSessionId()));
    workflowService.markInterviewFinished(applicationId, OperatorType.CANDIDATE, candidateId, "候选人结束语音面试");
    return getState(candidateId, applicationId);
  }

  /**
   * 语音 WebSocket 握手校验：令牌有效，且会话属于该候选人正在进行的面试
   */
  public boolean canAccessVoice(String token, Long voiceSessionId) {
    if (token == null || token.isBlank() || voiceSessionId == null) {
      return false;
    }
    try {
      Long candidateId = candidateAuthService.requireCandidateId(token);
      return applicationRepository.findByVoiceSessionId(voiceSessionId)
          .filter(app -> app.getCandidateId().equals(candidateId))
          .filter(app -> app.getStatus() == ApplicationStatus.INTERVIEWING)
          .isPresent();
    } catch (BusinessException e) {
      return false;
    }
  }

  // ========== 防作弊 ==========

  public void recordProctor(Long candidateId, Long applicationId, ProctorEventRequest request) {
    ApplicationEntity application = requireOwned(candidateId, applicationId);
    if (application.getStatus() != ApplicationStatus.INTERVIEWING) {
      return;
    }
    if (proctorEventRepository.countByApplicationId(applicationId) >= MAX_PROCTOR_EVENTS) {
      return;
    }
    ProctorEventEntity event = new ProctorEventEntity();
    event.setApplicationId(applicationId);
    event.setEventType(request.type());
    String detail = request.detail();
    event.setDetail(detail != null && detail.length() > 255 ? detail.substring(0, 255) : detail);
    event.setOccurredAt(LocalDateTime.now());
    proctorEventRepository.save(event);
  }

  // ========== 超时自动交卷（调度调用） ==========

  public void autoFinishOverdue(Long applicationId) {
    ApplicationEntity application = applicationRepository.findById(applicationId).orElse(null);
    if (application == null || application.getStatus() != ApplicationStatus.INTERVIEWING) {
      return;
    }
    if (application.getInterviewMode() == InterviewMode.TEXT && application.getInterviewSessionId() != null) {
      completeTextQuietly(application.getInterviewSessionId());
    } else if (application.getVoiceSessionId() != null) {
      voiceInterviewService.endSessionIfInProgress(String.valueOf(application.getVoiceSessionId()));
    }
    workflowService.markInterviewFinished(applicationId, OperatorType.SYSTEM, null, "作答时间结束，系统自动交卷");
  }

  /** 语音会话已结束（如断线兜底结束）时推进投递状态（调度调用） */
  public void finishIfVoiceEnded(Long applicationId, InterviewResultReader resultReader) {
    ApplicationEntity application = applicationRepository.findById(applicationId).orElse(null);
    if (application == null || application.getStatus() != ApplicationStatus.INTERVIEWING
        || application.getInterviewMode() != InterviewMode.VOICE || application.getVoiceSessionId() == null) {
      return;
    }
    if (resultReader.isVoiceSessionEnded(application.getVoiceSessionId())) {
      workflowService.markInterviewFinished(applicationId, OperatorType.SYSTEM, null, "语音面试已结束");
    }
  }

  private void completeTextQuietly(String sessionId) {
    try {
      interviewSessionService.completeInterview(sessionId);
    } catch (BusinessException e) {
      if (!ErrorCode.INTERVIEW_ALREADY_COMPLETED.getCode().equals(e.getCode())) {
        throw e;
      }
    }
  }

  // ========== 工具方法 ==========

  private ApplicationEntity requireOwned(Long candidateId, Long applicationId) {
    return applicationRepository.findByIdAndCandidateId(applicationId, candidateId)
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
  }

  private ApplicationEntity requireInterviewing(Long candidateId, Long applicationId, InterviewMode mode) {
    ApplicationEntity application = requireOwned(candidateId, applicationId);
    if (application.getStatus() != ApplicationStatus.INTERVIEWING || application.getInterviewMode() != mode) {
      throw new BusinessException(ErrorCode.INTERVIEW_NOT_AVAILABLE, "当前不在" + modeLabel(mode) + "中");
    }
    return application;
  }

  static String modeLabel(InterviewMode mode) {
    return mode == InterviewMode.VOICE ? "语音面试" : "文字面试";
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }
}
