package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.mail.model.EmailLogDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 后台投递详情（审核工作台）
 */
public record ApplicationDetailDTO(
    Long id,
    ApplicationStatus status,
    // 候选人
    Long candidateId,
    String candidateName,
    String candidateEmail,
    String candidatePhone,
    // 岗位
    Long jobId,
    String jobTitle,
    Integer aiPassThreshold,
    Integer screeningThreshold,
    // 简历
    Long resumeId,
    String resumeFilename,
    String resumeText,
    // AI 初筛
    Integer screeningScore,
    ScreeningResult screeningResult,
    // 在线面试
    InterviewMode interviewMode,
    String interviewSessionId,
    Long voiceSessionId,
    LocalDateTime interviewStartedAt,
    LocalDateTime interviewCompletedAt,
    // AI 决策
    Integer aiScore,
    AiDecision aiDecision,
    AiReviewRecord aiReview,
    String taskError,
    // HR
    Long hrReviewerId,
    String hrReviewerName,
    String hrComment,
    LocalDateTime hrReviewedAt,
    // 其他
    Map<ProctorEventType, Long> proctorCounts,
    List<ProctorEventDTO> proctorEvents,
    List<ApplicationStatusLogDTO> logs,
    OfflineInterviewDTO offlineInterview,
    List<EmailLogDTO> emails,
    LocalDateTime submittedAt,
    LocalDateTime inviteExpiresAt,
    LocalDateTime updatedAt
) {
}
