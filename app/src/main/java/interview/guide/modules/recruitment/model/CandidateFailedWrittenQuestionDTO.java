package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 候选人未通过投递后可见的在线笔试错题 / 未作答题解析
 */
public record CandidateFailedWrittenQuestionDTO(
    int index,
    WrittenQuestionType type,
    String question,
    List<String> options,
    String category,
    List<String> candidateAnswer,
    List<String> correctAnswers,
    String explanation,
    String judgeReason
) {
}
