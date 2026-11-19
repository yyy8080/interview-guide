package interview.guide.modules.recruitment.service;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.event.InterviewEvaluatedEvent;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.service.JobPositionService;
import interview.guide.modules.recruitment.listener.ApplicationTaskProducer;
import interview.guide.modules.recruitment.model.AiReviewRecord;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.ApplicationTaskType;
import interview.guide.modules.recruitment.model.OperatorType;
import interview.guide.modules.recruitment.model.ProctorRiskAssessment;
import interview.guide.modules.recruitment.model.ScreeningResult;
import interview.guide.modules.recruitment.model.WrittenTestEntity;
import interview.guide.modules.recruitment.model.WrittenTestStatus;
import interview.guide.modules.recruitment.model.WrittenTestSummary;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.ProctorEventRepository;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluationSnapshot;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluationState;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * 投递流程的系统侧推进：AI 初筛、面试结束、评估完成、AI 决策、邀请过期及各类兜底。
 * <p>
 * LLM 调用均在事务外执行；写回结果时加行锁并校验状态与任务执行代次（fencing），
 * 防止重复消费或过期任务覆盖新结果。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationWorkflowService {

  private final ApplicationRepository applicationRepository;
  private final JobPositionService jobPositionService;
  private final ResumeRepository resumeRepository;
  private final ProctorEventRepository proctorEventRepository;
  private final ApplicationStateMachine stateMachine;
  private final ResumeScreeningService screeningService;
  private final CandidateDecisionService decisionService;
  private final CandidateWrittenTestService writtenTestService;
  private final ProctorRiskService proctorRiskService;
  private final InterviewResultReader resultReader;
  private final ApplicationTaskProducer taskProducer;
  private final TransactionalExecutor transactionalExecutor;
  private final RecruitmentProperties properties;
  private final ObjectMapper objectMapper;

  // ========== 面试邀请 ==========

  /**
   * 进入在线面试邀请（调用方需处于事务中）
   */
  public void invite(ApplicationEntity application, OperatorType operatorType, Long operatorId, String remark) {
    LocalDateTime now = LocalDateTime.now();
    application.setInvitedAt(now);
    application.setInviteExpiresAt(now.plusDays(Math.max(properties.getInviteExpireDays(), 1)));
    stateMachine.transit(application, ApplicationStatus.INTERVIEW_INVITED, operatorType, operatorId, remark);
  }

  /** 进入初筛状态后（事务提交）投递初筛任务 */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onStatusChanged(ApplicationStatusChangedEvent event) {
    if (event.to() == ApplicationStatus.SCREENING) {
      taskProducer.send(event.applicationId(), ApplicationTaskType.SCREENING);
    }
  }

  // ========== AI 初筛 ==========

  /**
   * 执行 AI 初筛（任务消费者调用）；LLM 失败抛出异常交由消费者重试
   */
  public void runScreening(Long applicationId, String attemptId) {
    ApplicationEntity application = applicationRepository.findById(applicationId).orElse(null);
    if (application == null || application.getStatus() != ApplicationStatus.SCREENING) {
      return;
    }
    JobPositionEntity job = jobPositionService.getEntity(application.getJobId());
    ResumeEntity resume = resumeRepository.findById(application.getResumeId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
    ScreeningResult result = application.getCandidateTrack() == null
        ? screeningService.screen(job, resume.getResumeText(), application.getCandidateLevel())
        : screeningService.screen(job, resume.getResumeText(), application.getCandidateLevel(),
            application.getCandidateTrack());
    int threshold = job.getScreeningThreshold() != null
        ? job.getScreeningThreshold() : properties.getDefaultScreeningThreshold();

    transactionalExecutor.run(() -> {
      ApplicationEntity locked = lockOwned(applicationId, ApplicationStatus.SCREENING, attemptId);
      if (locked == null) {
        return;
      }
      locked.setScreeningScore(result.score());
      locked.setScreeningResultJson(toJson(result));
      locked.finishTask();
      String remark = "AI 初筛得分 " + result.score() + "（通过线 " + threshold + "）";
      if (result.score() >= threshold) {
        invite(locked, OperatorType.AI, null, remark + "，进入在线面试");
      } else {
        stateMachine.transit(locked, ApplicationStatus.SCREEN_REJECTED, OperatorType.AI, null, remark + "，未通过");
      }
    });
  }

  /**
   * 初筛多次失败兜底：不因系统故障淘汰候选人，直接进入在线面试
   *
   * @param attemptId 为空表示由恢复调度直接触发
   */
  public void screeningFallback(Long applicationId, String attemptId, String error) {
    transactionalExecutor.run(() -> {
      ApplicationEntity locked = applicationRepository.findByIdForUpdate(applicationId).orElse(null);
      if (locked == null || locked.getStatus() != ApplicationStatus.SCREENING) {
        return;
      }
      if (attemptId != null && locked.getTaskAttemptId() != null
          && !attemptId.equals(locked.getTaskAttemptId())) {
        return;
      }
      locked.finishTask();
      locked.setTaskError(truncate(error));
      invite(locked, OperatorType.SYSTEM, null, "AI 初筛暂不可用，自动进入在线面试环节");
    });
  }

  // ========== 面试结束 / 评估完成 ==========

  /**
   * 候选人交卷、作答超时或语音面试结束：INTERVIEWING → AI_REVIEWING（幂等）
   */
  public void markInterviewFinished(Long applicationId, OperatorType operatorType, Long operatorId, String remark) {
    transactionalExecutor.run(() -> {
      ApplicationEntity locked = applicationRepository.findByIdForUpdate(applicationId).orElse(null);
      if (locked == null || locked.getStatus() != ApplicationStatus.INTERVIEWING) {
        return;
      }
      locked.setTaskUpdatedAt(LocalDateTime.now());
      applyRiskQuietly(locked);
      stateMachine.transit(locked, ApplicationStatus.AI_REVIEWING, operatorType, operatorId, remark);
    });
  }

  /**
   * 面试评估结束：推进到 AI_REVIEWING 并投递 AI 决策任务；评估失败转人工审核
   */
  @EventListener
  public void onInterviewEvaluated(InterviewEvaluatedEvent event) {
    Optional<ApplicationEntity> found = event.type() == InterviewEvaluatedEvent.Type.VOICE
        ? parseLong(event.sessionId()).flatMap(applicationRepository::findByVoiceSessionId)
        : applicationRepository.findByInterviewSessionId(event.sessionId());
    if (found.isEmpty()) {
      return;
    }
    Long applicationId = found.get().getId();
    try {
      markInterviewFinished(applicationId, OperatorType.SYSTEM, null, "在线面试已结束");
      if (event.success()) {
        taskProducer.send(applicationId, ApplicationTaskType.DECISION);
      } else {
        evaluationFallback(applicationId, "面试评估失败，转人工审核");
      }
    } catch (Exception e) {
      log.error("处理面试评估结束事件失败，等待恢复调度: applicationId={}, error={}", applicationId, e.getMessage());
    }
  }

  /**
   * 评估失败 / 超时兜底：不做 AI 结论，直接交由 HR 人工审核
   */
  public void evaluationFallback(Long applicationId, String remark) {
    transactionalExecutor.run(() -> {
      ApplicationEntity locked = applicationRepository.findByIdForUpdate(applicationId).orElse(null);
      if (locked == null || locked.getStatus() != ApplicationStatus.AI_REVIEWING) {
        return;
      }
      locked.finishTask();
      locked.setAiDecision(null);
      locked.setAiResultJson(toJson(new AiReviewRecord(null, null, null, null, true, remark, Map.of(), null, null)));
      stateMachine.transit(locked, ApplicationStatus.AI_PASSED, OperatorType.SYSTEM, null, remark);
    });
  }

  // ========== AI 决策 ==========

  /**
   * 执行 AI 综合决策（任务消费者调用）
   *
   * @param skipLlm 为 true 时只按分数线判定
   */
  public void runDecision(Long applicationId, String attemptId, boolean skipLlm) {
    ApplicationEntity application = applicationRepository.findById(applicationId).orElse(null);
    if (application == null || application.getStatus() != ApplicationStatus.AI_REVIEWING) {
      return;
    }
    EvaluationSnapshot snapshot = resultReader.snapshot(application);
    if (snapshot.state() == EvaluationState.PENDING) {
      // 评估尚未完成：释放执行权，等待评估结束事件或恢复调度再次触发
      applicationRepository.releaseTask(applicationId, attemptId, "面试评估尚未完成", LocalDateTime.now());
      return;
    }
    if (snapshot.state() != EvaluationState.COMPLETED) {
      evaluationFallback(applicationId, "面试评估失败，转人工审核");
      return;
    }
    JobPositionEntity job = jobPositionService.getEntity(application.getJobId());
    ScreeningResult screening = parseScreening(application.getScreeningResultJson());
    Map<String, Long> proctorCounts = new LinkedHashMap<>();
    proctorEventRepository.countByType(applicationId)
        .forEach(c -> proctorCounts.put(c.getEventType().name(), c.getTotal()));

    ProctorRiskAssessment risk = proctorRiskService.assess(applicationId);
    WrittenTestSummary written = resolveWrittenSummary(applicationId, job, snapshot.score());

    AiReviewRecord record = decisionService.decide(application, job, screening, snapshot, proctorCounts, risk,
        written, skipLlm);

    transactionalExecutor.run(() -> {
      ApplicationEntity locked = lockOwned(applicationId, ApplicationStatus.AI_REVIEWING, attemptId);
      if (locked == null) {
        return;
      }
      locked.setAiScore(record.effectiveScore());
      if (record.written() != null) {
        locked.setWrittenScore(record.written().writtenScore());
      }
      locked.setAiDecision(record.finalDecision());
      locked.setAiResultJson(toJson(record));
      locked.setProctorRiskScore(risk.score());
      locked.setProctorRiskLevel(risk.level());
      locked.finishTask();
      if (record.finalDecision() == null) {
        stateMachine.transit(locked, ApplicationStatus.AI_PASSED, OperatorType.SYSTEM, null, record.note());
        return;
      }
      String scoreDesc;
      if (record.written() != null) {
        WrittenTestSummary w = record.written();
        scoreDesc = "综合分 " + w.finalScore() + "（笔试 " + w.writtenScore() + "×" + w.weight() + "% + 面试 "
            + w.interviewScore() + "×" + (100 - w.weight()) + "%，通过线 " + record.passThreshold() + "）";
      } else {
        scoreDesc = "面试得分 " + record.interviewScore() + "（通过线 " + record.passThreshold() + "）";
      }
      String remark = scoreDesc
          + (record.llm() != null ? "，AI 建议 " + record.llm().decision() : "")
          + (record.note() != null ? "，" + record.note() : "");
      ApplicationStatus target = switch (record.finalDecision()) {
        case PASS -> ApplicationStatus.AI_PASSED;
        case REJECT -> ApplicationStatus.AI_REJECTED;
      };
      stateMachine.transit(locked, target, OperatorType.AI, null, remark);
    });
  }

  private WrittenTestSummary resolveWrittenSummary(Long applicationId, JobPositionEntity job, Integer interviewScore) {
    if (interviewScore == null) {
      return null;
    }
    try {
      Optional<WrittenTestEntity> found = writtenTestService.ensureGraded(applicationId, job.getLlmProvider());
      if (found.isEmpty()) {
        return null;
      }
      WrittenTestEntity test = found.get();
      if (test.getStatus() != WrittenTestStatus.GRADED || test.getScore() == null) {
        return null;
      }
      int weight = job.getWrittenWeight();
      int finalScore = WrittenTestSummary.combine(test.getScore(), interviewScore, weight);
      return new WrittenTestSummary(test.getScore(), weight, interviewScore, finalScore,
          test.getCorrectCount() == null ? 0 : test.getCorrectCount(), test.getTotalCount());
    } catch (Exception e) {
      log.warn("读取或补判在线笔试成绩失败，按面试分继续决策: applicationId={}, error={}",
          applicationId, e.getMessage());
      return null;
    }
  }

  /** 决策任务多次失败兜底：仅按分数线判定，异常时释放执行权等待恢复 */
  public void runDecisionQuietly(Long applicationId, String attemptId) {
    try {
      runDecision(applicationId, attemptId, true);
    } catch (Exception e) {
      log.error("AI 决策兜底失败: applicationId={}, error={}", applicationId, e.getMessage());
      applicationRepository.releaseTask(applicationId, attemptId, truncate(e.getMessage()), LocalDateTime.now());
    }
  }

  // ========== 邀请过期 ==========

  public void expireInvitation(Long applicationId) {
    transactionalExecutor.run(() -> {
      ApplicationEntity locked = applicationRepository.findByIdForUpdate(applicationId).orElse(null);
      if (locked == null || locked.getStatus() != ApplicationStatus.INTERVIEW_INVITED
          || locked.getInviteExpiresAt() == null || locked.getInviteExpiresAt().isAfter(LocalDateTime.now())) {
        return;
      }
      stateMachine.transit(locked, ApplicationStatus.EXPIRED, OperatorType.SYSTEM, null, "面试邀请已过期");
    });
  }

  // ========== 工具方法 ==========

  /** 交卷时计算作弊风险分（行为数据此后不再变化）；失败不影响流程推进 */
  private void applyRiskQuietly(ApplicationEntity application) {
    try {
      ProctorRiskAssessment risk = proctorRiskService.assess(application.getId());
      application.setProctorRiskScore(risk.score());
      application.setProctorRiskLevel(risk.level());
    } catch (Exception e) {
      log.warn("计算作弊风险失败: applicationId={}, error={}", application.getId(), e.getMessage());
    }
  }

  /** 加锁读取并校验状态与执行代次；不匹配返回 null（结果作废） */
  private ApplicationEntity lockOwned(Long applicationId, ApplicationStatus expected, String attemptId) {
    ApplicationEntity locked = applicationRepository.findByIdForUpdate(applicationId).orElse(null);
    if (locked == null || locked.getStatus() != expected) {
      log.info("投递状态已变化，丢弃任务结果: applicationId={}, expected={}", applicationId, expected);
      return null;
    }
    if (attemptId != null && !Objects.equals(attemptId, locked.getTaskAttemptId())) {
      log.info("任务执行权已失效，丢弃任务结果: applicationId={}", applicationId);
      return null;
    }
    return locked;
  }

  ScreeningResult parseScreening(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, ScreeningResult.class);
    } catch (Exception e) {
      return null;
    }
  }

  private String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化审核结果失败");
    }
  }

  private static Optional<Long> parseLong(String value) {
    try {
      return Optional.of(Long.parseLong(value));
    } catch (NumberFormatException | NullPointerException e) {
      return Optional.empty();
    }
  }

  private static String truncate(String value) {
    if (value == null) {
      return null;
    }
    return value.length() > 500 ? value.substring(0, 500) : value;
  }
}
