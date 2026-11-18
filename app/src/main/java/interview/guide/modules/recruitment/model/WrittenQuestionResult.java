package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 单题判分结果
 *
 * @param index    题号
 * @param correct  是否正确
 * @param answer   候选人作答（选项字母或填空内容）
 * @param judgedBy 判定方式：RULE（规则）/ AI（语义判定）/ AI_FALLBACK（AI 判定失败按规则计分）/ UNANSWERED（未作答）
 * @param reason   判定说明（AI 判定时给出）
 */
public record WrittenQuestionResult(
    int index,
    boolean correct,
    List<String> answer,
    String judgedBy,
    String reason
) {

  public static final String RULE = "RULE";
  public static final String AI = "AI";
  public static final String AI_FALLBACK = "AI_FALLBACK";
  public static final String UNANSWERED = "UNANSWERED";
}
