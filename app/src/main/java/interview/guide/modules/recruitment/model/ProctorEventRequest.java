package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 候选人端上报的作答行为事件
 *
 * @param durationMs    离开 / 失焦时长或语音应答延迟（毫秒，最长按 6 小时截断）
 * @param questionIndex 事件发生时的题号
 * @param chars         粘贴字数 / 改写字数
 * @param pastedText    实际粘贴的文本内容
 */
public record ProctorEventRequest(
    @NotNull(message = "事件类型不能为空") ProctorEventType type,
    @Size(max = 255) String detail,
    @Min(0) @Max(21_600_000) Long durationMs,
    @Min(0) @Max(1000) Integer questionIndex,
    @Min(0) @Max(100_000) Integer chars,
    @Size(max = 8000, message = "粘贴文本过长") String pastedText
) {

  public ProctorEventRequest(ProctorEventType type, String detail) {
    this(type, detail, null, null, null, null);
  }

  public ProctorEventRequest(ProctorEventType type, String detail, Long durationMs, Integer questionIndex,
                             Integer chars) {
    this(type, detail, durationMs, questionIndex, chars, null);
  }
}
