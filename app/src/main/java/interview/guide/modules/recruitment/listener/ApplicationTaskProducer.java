package interview.guide.modules.recruitment.listener;

import interview.guide.common.async.AbstractStreamProducer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.recruitment.model.ApplicationTaskType;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 投递单异步任务生产者（AI 初筛 / AI 决策）。
 * 入队失败不改状态，由 RecruitmentScheduler 按 task_updated_at 补投。
 */
@Slf4j
@Component
public class ApplicationTaskProducer extends AbstractStreamProducer<ApplicationTaskProducer.TaskPayload> {

  record TaskPayload(Long applicationId, ApplicationTaskType type) {
  }

  public ApplicationTaskProducer(RedisService redisService) {
    super(redisService);
  }

  public boolean send(Long applicationId, ApplicationTaskType type) {
    return sendTask(new TaskPayload(applicationId, type));
  }

  @Override
  protected String taskDisplayName() {
    return "招聘投递";
  }

  @Override
  protected String streamKey() {
    return AsyncTaskStreamConstants.RECRUITMENT_TASK_STREAM_KEY;
  }

  @Override
  protected Map<String, String> buildMessage(TaskPayload payload) {
    return Map.of(
        AsyncTaskStreamConstants.FIELD_APPLICATION_ID, payload.applicationId().toString(),
        AsyncTaskStreamConstants.FIELD_TASK_TYPE, payload.type().name(),
        AsyncTaskStreamConstants.FIELD_RETRY_COUNT, "0");
  }

  @Override
  protected String payloadIdentifier(TaskPayload payload) {
    return "applicationId=" + payload.applicationId() + ", type=" + payload.type();
  }

  @Override
  protected void onSendFailed(TaskPayload payload, String error) {
    log.warn("招聘任务入队失败，等待恢复调度补投: {}", payloadIdentifier(payload));
  }
}
