package interview.guide.modules.mail.model;

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
 * 邮件发件箱 / 发送日志
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "email_logs")
public class EmailLogEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "application_id")
  private Long applicationId;

  @Column(name = "to_address", nullable = false)
  private String toAddress;

  @Column(name = "template_code", nullable = false, length = 64)
  private String templateCode;

  @Column(nullable = false)
  private String subject;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String body;

  /** 日历邀请附件（iCalendar 文本） */
  @Column(name = "attachment_ics", columnDefinition = "TEXT")
  private String attachmentIcs;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private EmailLogStatus status = EmailLogStatus.PENDING;

  @Column(length = 500)
  private String error;

  @Column(name = "retry_count", nullable = false)
  private int retryCount = 0;

  @Column(name = "next_attempt_at")
  private LocalDateTime nextAttemptAt;

  @Column(name = "sent_at")
  private LocalDateTime sentAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @PrePersist
  void prePersist() {
    createdAt = LocalDateTime.now();
  }
}
