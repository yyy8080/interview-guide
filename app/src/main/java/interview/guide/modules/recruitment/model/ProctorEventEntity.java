package interview.guide.modules.recruitment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 在线面试防作弊事件
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "interview_proctor_events")
public class ProctorEventEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "application_id", nullable = false)
  private Long applicationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "event_type", nullable = false, length = 32)
  private ProctorEventType eventType;

  @Column(length = 500)
  private String detail;

  /** 离开 / 失焦持续时长或语音应答延迟（毫秒） */
  @Column(name = "duration_ms")
  private Long durationMs;

  /** 事件发生时正在作答的题号（从 0 开始） */
  @Column(name = "question_index")
  private Integer questionIndex;

  /** 粘贴字数 / 语音改写字数 */
  private Integer chars;

  @Column(name = "occurred_at", nullable = false)
  private LocalDateTime occurredAt;
}
