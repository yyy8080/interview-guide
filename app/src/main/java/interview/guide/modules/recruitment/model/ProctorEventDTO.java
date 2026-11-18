package interview.guide.modules.recruitment.model;

import java.time.LocalDateTime;

public record ProctorEventDTO(Long id, ProctorEventType type, String detail, LocalDateTime occurredAt) {
}
