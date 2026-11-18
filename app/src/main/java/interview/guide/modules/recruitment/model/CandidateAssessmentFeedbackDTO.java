package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 候选人完成答题且最终未通过（AI_REJECTED / HR_REJECTED）时可见的评估未通过原因与未通过题目解析。
 * <p>
 * 安全脱敏：仅在终态未通过时返回，绝不在作答中或审核中提前暴露标准答案，且绝不暴露 HR 内部评语。
 */
public record CandidateAssessmentFeedbackDTO(
    String rejectStageLabel,
    String summary,
    String scoreSummary,
    List<String> reasons,
    List<CandidateFailedWrittenQuestionDTO> failedWrittenQuestions,
    List<CandidateFailedInterviewQuestionDTO> failedInterviewQuestions
) {
}
