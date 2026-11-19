package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 候选人视角的投递记录（在初筛未通过或答题终态未通过时附带脱敏的未通过原因及未通过题目解析，不暴露 HR 内部评语）
 */
public record CandidateApplicationDTO(
    Long id,
    Long jobId,
    String jobTitle,
    String department,
    String location,
    CandidateStage stage,
    String candidateLevel,
    String candidateTrack,
    String aiExperience,
    String specialties,
    List<InterviewMode> availableModes,
    InterviewMode interviewMode,
    Integer questionCount,
    Integer timeLimitMinutes,
    LocalDateTime submittedAt,
    LocalDateTime inviteExpiresAt,
    LocalDateTime interviewStartedAt,
    LocalDateTime interviewDeadlineAt,
    LocalDateTime interviewCompletedAt,
    CandidateOfflineDTO offlineInterview,
    CandidateScreeningFeedbackDTO screeningFeedback,
    CandidateAssessmentFeedbackDTO assessmentFeedback
) {
}
