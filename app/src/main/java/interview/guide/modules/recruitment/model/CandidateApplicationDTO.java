package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 候选人视角的投递记录（只暴露阶段，不含分数与审核细节）
 */
public record CandidateApplicationDTO(
    Long id,
    Long jobId,
    String jobTitle,
    String department,
    String location,
    CandidateStage stage,
    String candidateLevel,
    List<InterviewMode> availableModes,
    InterviewMode interviewMode,
    Integer questionCount,
    Integer timeLimitMinutes,
    LocalDateTime submittedAt,
    LocalDateTime inviteExpiresAt,
    LocalDateTime interviewStartedAt,
    LocalDateTime interviewDeadlineAt,
    LocalDateTime interviewCompletedAt,
    CandidateOfflineDTO offlineInterview
) {
}
