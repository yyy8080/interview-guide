package interview.guide.common.event;

/**
 * 面试评估结束事件（文字 / 语音），由评估消费者在写入终态后发布。
 * 招聘模块据此推进投递单的 AI 决策。
 *
 * @param type      TEXT 或 VOICE
 * @param sessionId 文字面试为 16 位会话 ID，语音面试为数字 ID 的字符串
 * @param success   评估是否成功
 */
public record InterviewEvaluatedEvent(Type type, String sessionId, boolean success) {

  public enum Type {
    TEXT,
    VOICE
  }
}
