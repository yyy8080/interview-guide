package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * AI 综合决策结构化输出（LLM）
 *
 * @param decision   PASS / REJECT
 * @param confidence 0-1
 */
public record DecisionResult(
    String decision,
    double confidence,
    String summary,
    List<String> reasons,
    List<String> risks,
    List<String> suggestedQuestions
) {
}
