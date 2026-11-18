package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.mail.model.EmailLogStatus;
import java.time.LocalDateTime;

/**
 * 后台面试邀请列表项（含邮件投递状态与候选人是否参加面试实时进度）
 */
public record InvitationListItemDTO(
    Long id,
    Long jobId,
    String jobTitle,
    String email,
    String candidateName,
    InvitationRecordStatus status,
    boolean skipScreening,
    String note,
    String invitedByName,
    LocalDateTime invitedAt,
    LocalDateTime expiresAt,
    LocalDateTime revokedAt,
    int sendCount,
    EmailLogStatus emailStatus,
    LocalDateTime emailSentAt,
    String emailError,
    boolean participated,
    InvitationParticipationStatus participationStatus,
    String participationDetail,
    Long applicationId,
    ApplicationStatus applicationStatus,
    InterviewMode interviewMode,
    Integer screeningScore,
    Integer writtenScore,
    Integer aiScore,
    LocalDateTime submittedAt,
    LocalDateTime interviewStartedAt,
    LocalDateTime interviewCompletedAt
) {
}
