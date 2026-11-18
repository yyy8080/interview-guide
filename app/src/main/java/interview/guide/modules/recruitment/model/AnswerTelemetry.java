package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 文字面试单题作答遥测（前端无感采集）
 *
 * @param durationMs       本题从展示到提交的总耗时
 * @param firstInputMs     展示后首次输入的延迟
 * @param typedChars       键盘输入字数
 * @param pastedChars      粘贴字数
 * @param maxBurstChars    非粘贴情况下 1 秒内最大新增字数（识别输入法云剪贴板 / 脚本灌入）
 * @param awayMs           本题作答期间离开页面 / 失焦累计时长
 */
public record AnswerTelemetry(
    @Min(0) @Max(86_400_000) Long durationMs,
    @Min(0) @Max(86_400_000) Long firstInputMs,
    @Min(0) @Max(200_000) Integer typedChars,
    @Min(0) @Max(200_000) Integer pastedChars,
    @Min(0) @Max(200_000) Integer maxBurstChars,
    @Min(0) @Max(86_400_000) Long awayMs
) {
}
