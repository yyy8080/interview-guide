package interview.guide.modules.recruitment.model;

/**
 * 后台展示的逐题作答遥测
 *
 * @param pasteRatio 粘贴字数占（键入 + 粘贴）字数的比例 0-1，无输入时为空
 * @param flagged    该题是否被风险引擎标记为可疑
 */
public record AnswerTelemetryDTO(
    int questionIndex,
    Long durationMs,
    Long firstInputMs,
    Integer typedChars,
    Integer pastedChars,
    Integer maxBurstChars,
    Long awayMs,
    Integer answerChars,
    Double pasteRatio,
    boolean flagged
) {
}
