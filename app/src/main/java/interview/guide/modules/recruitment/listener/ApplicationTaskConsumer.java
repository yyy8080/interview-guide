package interview.guide.modules.recruitment.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.recruitment.model.ApplicationTaskType;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.service.ApplicationWorkflowService;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

/**
 * 投递单异步任务消费者：条件领取（执行代次 fencing）→ 执行 AI 初筛 / 决策 → 写回结果并推进状态
 */
@Slf4j
@Component
public class ApplicationTaskConsumer extends AbstractStreamConsumer<ApplicationTaskConsumer.TaskPayload> {

  private static final long CLAIM_STALE_MINUTES = 10;

  private final ApplicationRepository applicationRepository;
  private final ApplicationWorkflowService workflowService;

  public ApplicationTaskConsumer(RedisService redisService, ApplicationRepository applicationRepository,
                                 ApplicationWorkflowService workflowService) {
    super(redisService);
    this.applicationRepository = applicationRepository;
    this.workflowService = workflowService;
  }

  static final class TaskPayload {

    private final Long applicationId;
    private final ApplicationTaskType type;
    private String attemptId;

    TaskPayload(Long applicationId, ApplicationTaskType type) {
      this.applicationId = applicationId;
      this.type = type;
    }
  }

  @Override
  protected String taskDisplayName() {
    return "招聘投递任务";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.RECRUITMENT_TASK_STREAM_KEY;
  }

  @Override
  protected String groupName() {
    return AsyncTaskStreamConstants.RECRUITMENT_TASK_GROUP_NAME;
  }

  @Override
  protected String consumerPrefix() {
    return AsyncTaskStreamConstants.RECRUITMENT_TASK_CONSUMER_PREFIX;
  }

  @Override
  protected String threadName() {
    return "recruitment-task-consumer";
  }

  @Override
  protected TaskPayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
    String id = data.get(AsyncTaskStreamConstants.FIELD_APPLICATION_ID);
    String type = data.get(AsyncTaskStreamConstants.FIELD_TASK_TYPE);
    if (id == null || type == null) {
      return null;
    }
    return new TaskPayload(Long.parseLong(id), ApplicationTaskType.valueOf(type));
  }

  @Override
  protected String payloadIdentifier(TaskPayload payload) {
    return "applicationId=" + payload.applicationId + ", type=" + payload.type;
  }

  @Override
  protected boolean shouldSkip(TaskPayload payload) {
    return applicationRepository.findById(payload.applicationId)
        .map(app -> app.getStatus() != payload.type.requiredStatus())
        .orElse(true);
  }

  @Override
  protected void markProcessing(TaskPayload payload) {
    // 由 tryMarkProcessing 条件领取
  }

  @Override
  protected boolean tryMarkProcessing(TaskPayload payload) {
    String attemptId = UUID.randomUUID().toString();
    LocalDateTime now = LocalDateTime.now();
    boolean claimed = applicationRepository.claimTask(payload.applicationId, payload.type.requiredStatus(),
        attemptId, now, now.minusMinutes(CLAIM_STALE_MINUTES)) == 1;
    if (claimed) {
      payload.attemptId = attemptId;
    }
    return claimed;
  }

  @Override
  protected void processBusiness(TaskPayload payload) {
    switch (payload.type) {
      case SCREENING -> workflowService.runScreening(payload.applicationId, payload.attemptId);
      case DECISION -> workflowService.runDecision(payload.applicationId, payload.attemptId, false);
      default -> throw new BusinessException(ErrorCode.INTERNAL_ERROR, "未知任务类型");
    }
  }

  @Override
  protected void markCompleted(TaskPayload payload) {
    // 结果与状态已在 processBusiness 中按执行代次写回
  }

  @Override
  protected void markFailed(TaskPayload payload, String error) {
    switch (payload.type) {
      case SCREENING -> workflowService.screeningFallback(payload.applicationId, payload.attemptId, error);
      case DECISION -> workflowService.runDecisionQuietly(payload.applicationId, payload.attemptId);
      default -> applicationRepository.releaseTask(payload.applicationId, payload.attemptId,
          truncateError(error), LocalDateTime.now());
    }
  }

  @Override
  protected void retryMessage(TaskPayload payload, int retryCount) {
    applicationRepository.releaseTask(payload.applicationId, payload.attemptId,
        "第 " + retryCount + " 次重试", LocalDateTime.now());
    try {
      redisService().streamAdd(streamKey(), Map.of(
          AsyncTaskStreamConstants.FIELD_APPLICATION_ID, payload.applicationId.toString(),
          AsyncTaskStreamConstants.FIELD_TASK_TYPE, payload.type.name(),
          AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)),
          AsyncTaskStreamConstants.STREAM_MAX_LEN);
    } catch (Exception e) {
      log.error("招聘任务重新入队失败，等待恢复调度: {}, error={}", payloadIdentifier(payload), e.getMessage());
    }
  }
}
