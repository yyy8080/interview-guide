package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 后台笔试逐题详情（含标准答案、候选人作答与判分结果）
 */
public record WrittenTestItemDTO(
    int index,
    WrittenQuestionType type,
    String question,
    List<String> options,
    List<String> correctAnswers,
    String explanation,
    String category,
    List<String> candidateAnswer,
    Boolean correct,
    String judgedBy,
    String reason
) {
}
