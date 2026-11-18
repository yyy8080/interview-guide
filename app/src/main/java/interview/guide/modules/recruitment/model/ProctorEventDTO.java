package interview.guide.modules.recruitment.model;

import java.time.LocalDateTime;

public record ProctorEventDTO(
    Long id,
    ProctorEventType type,
    String detail,
    Long durationMs,
    Integer questionIndex,
    Integer chars,
    String pastedText,
    LocalDateTime occurredAt
) {

  public ProctorEventDTO(Long id, ProctorEventType type, String detail, Long durationMs, Integer questionIndex,
                         Integer chars, LocalDateTime occurredAt) {
    this(id, type, detail, durationMs, questionIndex, chars, null, occurredAt);
  }
}
