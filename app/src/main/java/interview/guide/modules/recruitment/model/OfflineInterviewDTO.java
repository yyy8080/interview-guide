package interview.guide.modules.recruitment.model;

import java.time.LocalDateTime;

public record OfflineInterviewDTO(
    Long scheduleId,
    LocalDateTime interviewTime,
    Integer durationMinutes,
    String location,
    String interviewType,
    String interviewer,
    Integer roundNumber,
    String notes,
    String confirmStatus,
    String candidateNote,
    LocalDateTime confirmedAt
) {
}
