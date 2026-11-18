package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * AI 简历初筛结构化输出
 */
public record ScreeningResult(
    int score,
    String summary,
    List<String> strengths,
    List<String> risks,
    List<String> matchedRequirements,
    List<String> missingRequirements
) {
}
