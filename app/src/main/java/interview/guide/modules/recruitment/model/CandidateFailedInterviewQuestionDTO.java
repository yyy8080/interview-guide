package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 候选人未通过投递后可见的在线面试（文字 / 语音）失分题目解析
 */
public record CandidateFailedInterviewQuestionDTO(
    int questionIndex,
    String category,
    String question,
    String userAnswer,
    Integer score,
    int passThreshold,
    String feedback,
    String referenceAnswer,
    List<String> keyPoints
) {
}
