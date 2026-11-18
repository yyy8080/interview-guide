package interview.guide.modules.recruitment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 文字面试逐题作答遥测
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "interview_answer_telemetry")
public class AnswerTelemetryEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "application_id", nullable = false)
  private Long applicationId;

  @Column(name = "question_index", nullable = false)
  private Integer questionIndex;

  @Column(name = "duration_ms")
  private Long durationMs;

  @Column(name = "first_input_ms")
  private Long firstInputMs;

  @Column(name = "typed_chars")
  private Integer typedChars;

  @Column(name = "pasted_chars")
  private Integer pastedChars;

  @Column(name = "max_burst_chars")
  private Integer maxBurstChars;

  @Column(name = "away_ms")
  private Long awayMs;

  /** 服务端统计的最终答案字数 */
  @Column(name = "answer_chars")
  private Integer answerChars;

  @Column(name = "created_at", nullable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void prePersist() {
    if (createdAt == null) {
      createdAt = LocalDateTime.now();
    }
  }
}
