package interview.guide.modules.recruitment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * 投递状态变更审计日志
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "application_status_logs")
public class ApplicationStatusLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "application_id", nullable = false)
  private Long applicationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "from_status", length = 32)
  private ApplicationStatus fromStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "to_status", nullable = false, length = 32)
  private ApplicationStatus toStatus;

  @Enumerated(EnumType.STRING)
  @Column(name = "operator_type", nullable = false, length = 16)
  private OperatorType operatorType;

  @Column(name = "operator_id")
  private Long operatorId;

  @Column(length = 1000)
  private String remark;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void prePersist() {
    createdAt = LocalDateTime.now();
  }
}
