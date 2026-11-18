package interview.guide.modules.recruitment.model;

import java.time.LocalDateTime;

public record CandidateOfflineDTO(
    LocalDateTime interviewTime,
    Integer durationMinutes,
    String location,
    String interviewer,
    String notes,
    String confirmStatus,
    String candidateNote
) {
}
