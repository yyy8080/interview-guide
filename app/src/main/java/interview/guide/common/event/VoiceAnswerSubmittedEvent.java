package interview.guide.common.event;

/**
 * 招聘候选人语音面试中，提交文本与语音识别结果不一致时发布（同步事件）。
 * 招聘模块据此计算改写比例并记录作答行为。
 *
 * @param sessionId      语音面试会话 ID（数字字符串）
 * @param recognizedText 本轮语音识别文本
 * @param submittedText  候选人最终提交的文本
 */
public record VoiceAnswerSubmittedEvent(String sessionId, String recognizedText, String submittedText) {
}
