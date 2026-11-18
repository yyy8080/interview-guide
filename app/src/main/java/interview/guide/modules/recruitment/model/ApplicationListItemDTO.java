package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import java.time.LocalDateTime;

public record ApplicationListItemDTO(
    Long id,
    Long jobId,
    String jobTitle,
    String candidateName,
    String candidateEmail,
    String candidatePhone,
    ApplicationStatus status,
    InterviewMode interviewMode,
    Integer screeningScore,
    Integer writtenScore,
    Integer aiScore,
    AiDecision aiDecision,
    Integer proctorRiskScore,
    ProctorRiskLevel proctorRiskLevel,
    LocalDateTime submittedAt,
    LocalDateTime updatedAt
) {
}
