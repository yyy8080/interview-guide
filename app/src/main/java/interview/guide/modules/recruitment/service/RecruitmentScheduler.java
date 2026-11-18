package interview.guide.modules.recruitment.service;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.modules.recruitment.listener.ApplicationTaskProducer;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.ApplicationTaskType;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.WrittenTestRepository;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluationState;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 招聘流程调度：邀请过期、笔试 / 面试作答超时自动交卷、语音会话结束兜底、卡住的初筛 / 决策任务恢复
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentScheduler {

  private final ApplicationRepository applicationRepository;
  private final WrittenTestRepository writtenTestRepository;
  private final ApplicationWorkflowService workflowService;
  private final CandidateInterviewService interviewService;
  private final CandidateWrittenTestService writtenTestService;
  private final InterviewResultReader resultReader;
  private final ApplicationTaskProducer taskProducer;
  private final RecruitmentProperties properties;

  /** 笔试超时交卷、邀请过期、面试超时、语音会话已结束 */
  @Scheduled(fixedDelayString = "${app.recruitment.lifecycle-interval-ms:60000}",
      initialDelayString = "${app.recruitment.lifecycle-initial-delay-ms:30000}")
  public void advanceLifecycle() {
    if (!properties.getTask().isRecoveryEnabled()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    PageRequest batch = PageRequest.of(0, properties.getTask().getBatchSize());
    each(writtenTestRepository.findOverdueApplicationIds(now.minus(CandidateWrittenTestService.SUBMIT_GRACE), batch),
        writtenTestService::autoSubmitOverdue, "笔试超时交卷");
    each(applicationRepository.findExpiredInvitationIds(now, batch), workflowService::expireInvitation,
        "邀请过期处理");
    each(applicationRepository.findOverdueInterviewIds(now, batch), interviewService::autoFinishOverdue,
        "作答超时交卷");
    each(applicationRepository.findIdsByStatus(ApplicationStatus.INTERVIEWING, batch),
        id -> interviewService.finishIfVoiceEnded(id, resultReader), "语音会话结束检查");
  }

  /** 恢复卡住的初筛 / AI 决策任务 */
  @Scheduled(fixedDelayString = "${app.recruitment.recovery-interval-ms:120000}",
      initialDelayString = "${app.recruitment.recovery-initial-delay-ms:60000}")
  public void recoverStaleTasks() {
    if (!properties.getTask().isRecoveryEnabled()) {
      return;
    }
    LocalDateTime now = LocalDateTime.now();
    LocalDateTime staleBefore = now.minus(properties.getTask().getStaleAfter());
    PageRequest batch = PageRequest.of(0, properties.getTask().getBatchSize());

    each(applicationRepository.findStaleTaskIds(List.of(ApplicationStatus.SCREENING), staleBefore, batch),
        id -> recoverScreening(id, staleBefore), "初筛任务恢复");
    each(applicationRepository.findStaleTaskIds(List.of(ApplicationStatus.AI_REVIEWING), staleBefore, batch),
        id -> recoverDecision(id, staleBefore), "AI 决策任务恢复");
  }

  void recoverScreening(Long id, LocalDateTime staleBefore) {
    ApplicationEntity app = applicationRepository.findById(id).orElse(null);
    if (app == null || app.getStatus() != ApplicationStatus.SCREENING) {
      return;
    }
    if (app.getTaskRetryCount() >= properties.getTask().getMaxRecoveryAttempts()) {
      workflowService.screeningFallback(id, null, "AI 初筛多次超时未完成");
      return;
    }
    if (applicationRepository.markRequeued(id, ApplicationStatus.SCREENING, LocalDateTime.now(), staleBefore) == 1) {
      log.info("重新投递初筛任务: applicationId={}, retry={}", id, app.getTaskRetryCount() + 1);
      taskProducer.send(id, ApplicationTaskType.SCREENING);
    }
  }

  void recoverDecision(Long id, LocalDateTime staleBefore) {
    ApplicationEntity app = applicationRepository.findById(id).orElse(null);
    if (app == null || app.getStatus() != ApplicationStatus.AI_REVIEWING) {
      return;
    }
    EvaluationState state = resultReader.state(app);
    switch (state) {
      case COMPLETED -> {
        if (app.getTaskRetryCount() < properties.getTask().getMaxRecoveryAttempts()) {
          if (applicationRepository.markRequeued(id, ApplicationStatus.AI_REVIEWING, LocalDateTime.now(),
              staleBefore) == 1) {
            log.info("重新投递 AI 决策任务: applicationId={}, retry={}", id, app.getTaskRetryCount() + 1);
            taskProducer.send(id, ApplicationTaskType.DECISION);
          }
        } else {
          String attemptId = UUID.randomUUID().toString();
          if (applicationRepository.claimTask(id, ApplicationStatus.AI_REVIEWING, attemptId, LocalDateTime.now(),
              staleBefore) == 1) {
            log.warn("AI 决策多次失败，按分数线兜底判定: applicationId={}", id);
            workflowService.runDecisionQuietly(id, attemptId);
          }
        }
      }
      case FAILED, MISSING -> workflowService.evaluationFallback(id, "面试评估失败，转人工审核");
      case PENDING -> {
        LocalDateTime completedAt = app.getInterviewCompletedAt();
        if (completedAt != null
            && completedAt.isBefore(LocalDateTime.now().minus(properties.getTask().getEvaluationTimeout()))) {
          workflowService.evaluationFallback(id, "评估超时，转人工审核");
        }
      }
    }
  }

  private static void each(List<Long> ids, Consumer<Long> action, String label) {
    for (Long id : ids) {
      try {
        action.accept(id);
      } catch (Exception e) {
        log.error("{}失败: applicationId={}, error={}", label, id, e.getMessage());
      }
    }
  }
}
