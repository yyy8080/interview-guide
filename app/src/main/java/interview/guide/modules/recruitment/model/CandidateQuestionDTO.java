package interview.guide.modules.recruitment.model;

/**
 * 候选人可见的题目（不含参考答案、评分要点等）
 */
public record CandidateQuestionDTO(int questionIndex, String question, String category, boolean followUp) {
}
