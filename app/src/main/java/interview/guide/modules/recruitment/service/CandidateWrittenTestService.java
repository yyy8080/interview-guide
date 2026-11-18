package interview.guide.modules.recruitment.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.service.JobPositionService;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateLevels;
import interview.guide.modules.recruitment.model.CandidateWrittenQuestionDTO;
import interview.guide.modules.recruitment.model.CandidateWrittenTestDTO;
import interview.guide.modules.recruitment.model.WrittenAnswersRequest;
import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionType;
import interview.guide.modules.recruitment.model.WrittenTestEntity;
import interview.guide.modules.recruitment.model.WrittenTestStatus;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.WrittenTestRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 候选人在线笔试：AI 实时出题 → 作答（自动保存）→ 交卷 → 自动判分。
 * <p>
 * 笔试位于 AI 面试之前，投递状态保持 INTERVIEW_INVITED；交卷后候选人才能开始文字 / 语音面试。
 * 判分结果在 AI 综合决策时与面试分按岗位权重合成综合分。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateWrittenTestService {

  /** 交卷对截止时间的宽限（网络延迟） */
  static final Duration SUBMIT_GRACE = Duration.ofSeconds(30);

  /** 笔试结束后至少保留的面试邀请有效期，避免临近过期开考导致无法进入面试 */
  static final Duration INVITE_BUFFER_AFTER_TEST = Duration.ofHours(2);

  private final ApplicationRepository applicationRepository;
  private final WrittenTestRepository writtenTestRepository;
  private final JobPositionService jobPositionService;
  private final WrittenTestGenerator generator;
  private final WrittenTestGrader grader;
  private final WrittenTestCodec codec;
  private final TransactionalExecutor transactionalExecutor;
  private final RedisService redisService;

  // ========== 开始笔试 ==========

  public CandidateWrittenTestDTO start(Long candidateId, Long applicationId) {
    return start(candidateId, applicationId, null);
  }

  /**
   * 开始笔试：校验邀请 → 事务外 AI 按候选人定位出题 → 事务内保存题目（幂等，重复调用返回已有笔试）
   */
  public CandidateWrittenTestDTO start(Long candidateId, Long applicationId, String candidateLevel) {
    String requestedLevel = CandidateLevels.normalizeOrNull(candidateLevel);
    requireOwned(candidateId, applicationId);
    if (writtenTestRepository.findByApplicationId(applicationId).isPresent()) {
      return getState(candidateId, applicationId);
    }
    String lockKey = "recruitment:written:start:" + applicationId;
    try {
      redisService.executeWithLock(lockKey, 1, 300, TimeUnit.SECONDS,
          () -> doStart(candidateId, applicationId, requestedLevel));
    } catch (BusinessException e) {
      if (ErrorCode.INTERNAL_ERROR.getCode().equals(e.getCode()) && e.getMessage() != null
          && e.getMessage().startsWith("获取锁")) {
        throw new BusinessException(ErrorCode.WRITTEN_TEST_NOT_AVAILABLE, "笔试题目正在生成中，请稍后刷新");
      }
      throw e;
    }
    return getState(candidateId, applicationId);
  }

  private Void doStart(Long candidateId, Long applicationId, String requestedLevel) {
    if (writtenTestRepository.findByApplicationId(applicationId).isPresent()) {
      return null;
    }
    ApplicationEntity application = requireOwned(candidateId, applicationId);
    assertStartable(application);
    JobPositionEntity job = jobPositionService.getEntity(application.getJobId());
    if (!job.isWrittenTestEnabled()) {
      throw new BusinessException(ErrorCode.WRITTEN_TEST_NOT_AVAILABLE, "该岗位无需笔试");
    }
    String effectiveLevel = CandidateLevels.resolve(
        requestedLevel != null ? requestedLevel : application.getCandidateLevel(),
        job.getDifficulty());
    List<WrittenQuestion> questions = generator.generate(job, jobPositionService.readCategories(job), effectiveLevel);

    transactionalExecutor.run(() -> {
      ApplicationEntity locked = applicationRepository.findByIdForUpdate(applicationId)
          .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
      assertStartable(locked);
      if (writtenTestRepository.findByApplicationId(applicationId).isPresent()) {
        return;
      }
      locked.setCandidateLevel(effectiveLevel);
      LocalDateTime now = LocalDateTime.now();
      LocalDateTime deadline = now.plusMinutes(Math.max(5, job.getWrittenTimeLimitMinutes()));
      WrittenTestEntity test = new WrittenTestEntity();
      test.setApplicationId(applicationId);
      test.setStatus(WrittenTestStatus.IN_PROGRESS);
      test.setQuestionsJson(codec.write(questions));
      test.setAnswersJson(codec.write(Map.of()));
      test.setTotalCount(questions.size());
      test.setStartedAt(now);
      test.setDeadlineAt(deadline);
      writtenTestRepository.save(test);
      // 保证笔试结束后仍有足够时间进入面试
      LocalDateTime minInviteExpiry = deadline.plus(INVITE_BUFFER_AFTER_TEST);
      if (locked.getInviteExpiresAt() != null && locked.getInviteExpiresAt().isBefore(minInviteExpiry)) {
        locked.setInviteExpiresAt(minInviteExpiry);
      }
    });
    log.info("候选人开始笔试: applicationId={}, level={}, questions={}", applicationId, effectiveLevel, questions.size());
    return null;
  }

  private void assertStartable(ApplicationEntity application) {
    if (application.getStatus() != ApplicationStatus.INTERVIEW_INVITED) {
      throw new BusinessException(ErrorCode.WRITTEN_TEST_NOT_AVAILABLE);
    }
    if (application.getInviteExpiresAt() != null && application.getInviteExpiresAt().isBefore(LocalDateTime.now())) {
      throw new BusinessException(ErrorCode.WRITTEN_TEST_NOT_AVAILABLE, "面试邀请已过期");
    }
  }

  // ========== 笔试状态 ==========

  public CandidateWrittenTestDTO getState(Long candidateId, Long applicationId) {
    ApplicationEntity application = requireOwned(candidateId, applicationId);
    JobPositionEntity job = jobPositionService.getEntity(application.getJobId());
    String effectiveLevel = CandidateLevels.resolve(application.getCandidateLevel(), job.getDifficulty());
    Optional<WrittenTestEntity> found = writtenTestRepository.findByApplicationId(applicationId);
    if (found.isPresent() && isOverdue(found.get())) {
      submitAndGrade(found.get(), job.getLlmProvider());
      found = writtenTestRepository.findByApplicationId(applicationId);
    }
    String watermark = job.isProctorWatermark() ? CandidateInterviewService.watermarkText(application) : null;
    if (found.isEmpty()) {
      int planned = job.getWrittenSingleCount() + job.getWrittenMultiCount() + job.getWrittenFillCount();
      return new CandidateWrittenTestDTO(applicationId, effectiveLevel, null, planned, List.of(), Map.of(), null,
          null, null, watermark, job.isProctorAwayReminder());
    }
    WrittenTestEntity test = found.get();
    boolean inProgress = test.getStatus() == WrittenTestStatus.IN_PROGRESS;
    List<CandidateWrittenQuestionDTO> questions = inProgress
        ? codec.readQuestions(test.getQuestionsJson()).stream().map(CandidateWrittenQuestionDTO::of).toList()
        : List.of();
    Long remaining = inProgress
        ? Math.max(0, Duration.between(LocalDateTime.now(), test.getDeadlineAt()).toSeconds())
        : null;
    return new CandidateWrittenTestDTO(applicationId, effectiveLevel, test.getStatus(), test.getTotalCount(),
        questions, inProgress ? codec.readAnswers(test.getAnswersJson()) : Map.of(), test.getStartedAt(),
        test.getDeadlineAt(), remaining, watermark, job.isProctorAwayReminder());
  }

  // ========== 作答 ==========

  /** 自动保存作答（覆盖式） */
  public void saveAnswers(Long candidateId, Long applicationId, WrittenAnswersRequest request) {
    requireOwned(candidateId, applicationId);
    WrittenTestEntity test = requireInProgress(applicationId);
    if (isOverdue(test)) {
      submitAndGrade(test, llmProviderOf(applicationId));
      throw new BusinessException(ErrorCode.WRITTEN_TEST_NOT_AVAILABLE, "笔试时间已结束，系统已自动交卷");
    }
    List<WrittenQuestion> questions = codec.readQuestions(test.getQuestionsJson());
    writtenTestRepository.saveAnswersIfInProgress(test.getId(),
        codec.write(sanitizeAnswers(questions, request.answers())), LocalDateTime.now());
  }

  /** 交卷：先保存最后一次作答（截止时间宽限内），再判分；重复交卷幂等 */
  public CandidateWrittenTestDTO submit(Long candidateId, Long applicationId, WrittenAnswersRequest request) {
    requireOwned(candidateId, applicationId);
    WrittenTestEntity test = writtenTestRepository.findByApplicationId(applicationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.WRITTEN_TEST_NOT_AVAILABLE, "尚未开始笔试"));
    if (test.getStatus() == WrittenTestStatus.IN_PROGRESS) {
      if (request != null && request.answers() != null && !isOverdue(test)) {
        List<WrittenQuestion> questions = codec.readQuestions(test.getQuestionsJson());
        writtenTestRepository.saveAnswersIfInProgress(test.getId(),
            codec.write(sanitizeAnswers(questions, request.answers())), LocalDateTime.now());
      }
      submitAndGrade(test, llmProviderOf(applicationId));
    }
    return getState(candidateId, applicationId);
  }

  // ========== 调度 / 决策调用 ==========

  /** 超时未交卷的笔试自动交卷并判分（调度调用） */
  public void autoSubmitOverdue(Long applicationId) {
    writtenTestRepository.findByApplicationId(applicationId)
        .filter(this::isOverdue)
        .ifPresent(test -> submitAndGrade(test, llmProviderOf(applicationId)));
  }

  /**
   * 确保笔试已判分（AI 决策前调用）：作答中则先交卷，已交卷未判分则补判分
   *
   * @return 最新的笔试记录；没有笔试返回空
   */
  public Optional<WrittenTestEntity> ensureGraded(Long applicationId, String llmProvider) {
    Optional<WrittenTestEntity> found = writtenTestRepository.findByApplicationId(applicationId);
    if (found.isEmpty() || found.get().getStatus() == WrittenTestStatus.GRADED) {
      return found;
    }
    WrittenTestEntity test = found.get();
    if (test.getStatus() == WrittenTestStatus.IN_PROGRESS) {
      writtenTestRepository.markSubmitted(test.getId(), LocalDateTime.now());
    }
    gradeQuietly(test.getId(), llmProvider);
    return writtenTestRepository.findByApplicationId(applicationId);
  }

  // ========== 内部实现 ==========

  private void submitAndGrade(WrittenTestEntity test, String llmProvider) {
    if (writtenTestRepository.markSubmitted(test.getId(), LocalDateTime.now()) == 1) {
      log.info("笔试已交卷: applicationId={}", test.getApplicationId());
      gradeQuietly(test.getId(), llmProvider);
    }
  }

  /** 判分失败保持 SUBMITTED，AI 决策前会再次判分 */
  void gradeQuietly(Long testId, String llmProvider) {
    try {
      WrittenTestEntity test = writtenTestRepository.findById(testId).orElse(null);
      if (test == null || test.getStatus() != WrittenTestStatus.SUBMITTED) {
        return;
      }
      List<WrittenQuestion> questions = codec.readQuestions(test.getQuestionsJson());
      WrittenTestGrader.GradeResult result = grader.grade(questions, codec.readAnswers(test.getAnswersJson()),
          llmProvider);
      transactionalExecutor.run(() -> {
        WrittenTestEntity fresh = writtenTestRepository.findById(testId).orElse(null);
        if (fresh == null || fresh.getStatus() != WrittenTestStatus.SUBMITTED) {
          return;
        }
        fresh.setResultsJson(codec.write(result.results()));
        fresh.setScore(result.score());
        fresh.setCorrectCount(result.correctCount());
        fresh.setStatus(WrittenTestStatus.GRADED);
        fresh.setGradedAt(LocalDateTime.now());
        writtenTestRepository.save(fresh);
        applicationRepository.findById(fresh.getApplicationId()).ifPresent(app -> {
          app.setWrittenScore(result.score());
          applicationRepository.save(app);
        });
      });
      log.info("笔试判分完成: applicationId={}, score={}", test.getApplicationId(), result.score());
    } catch (Exception e) {
      log.warn("笔试判分失败，等待 AI 决策前重试: testId={}, error={}", testId, e.getMessage());
    }
  }

  /**
   * 作答规范化：只保留存在的题号；选择题保留合法选项字母（单选只取一个）；填空题取第一项并截断
   */
  static Map<Integer, List<String>> sanitizeAnswers(List<WrittenQuestion> questions,
                                                   Map<Integer, List<String>> answers) {
    Map<Integer, List<String>> result = new LinkedHashMap<>();
    if (answers == null) {
      return result;
    }
    for (WrittenQuestion q : questions) {
      List<String> raw = answers.get(q.index());
      if (raw == null || raw.isEmpty()) {
        continue;
      }
      if (q.type() == WrittenQuestionType.FILL_BLANK) {
        String text = raw.get(0) == null ? "" : raw.get(0).trim();
        if (!text.isEmpty()) {
          result.put(q.index(), List.of(text.length() > 500 ? text.substring(0, 500) : text));
        }
        continue;
      }
      List<String> letters = WrittenTestGrader.normalizeLetters(raw, q.options() == null ? 0 : q.options().size());
      if (q.type() == WrittenQuestionType.SINGLE_CHOICE && letters.size() > 1) {
        letters = new ArrayList<>(letters.subList(0, 1));
      }
      if (!letters.isEmpty()) {
        result.put(q.index(), List.copyOf(letters));
      }
    }
    return result;
  }

  private boolean isOverdue(WrittenTestEntity test) {
    return test.getStatus() == WrittenTestStatus.IN_PROGRESS
        && LocalDateTime.now().isAfter(test.getDeadlineAt().plus(SUBMIT_GRACE));
  }

  private WrittenTestEntity requireInProgress(Long applicationId) {
    WrittenTestEntity test = writtenTestRepository.findByApplicationId(applicationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.WRITTEN_TEST_NOT_AVAILABLE, "尚未开始笔试"));
    if (test.getStatus() != WrittenTestStatus.IN_PROGRESS) {
      throw new BusinessException(ErrorCode.WRITTEN_TEST_NOT_AVAILABLE, "笔试已交卷");
    }
    return test;
  }

  private String llmProviderOf(Long applicationId) {
    return applicationRepository.findById(applicationId)
        .map(app -> jobPositionService.getEntity(app.getJobId()).getLlmProvider())
        .orElse(null);
  }

  private ApplicationEntity requireOwned(Long candidateId, Long applicationId) {
    return applicationRepository.findByIdAndCandidateId(applicationId, candidateId)
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
  }
}
