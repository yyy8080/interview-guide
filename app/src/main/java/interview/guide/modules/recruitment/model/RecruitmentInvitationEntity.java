package interview.guide.modules.recruitment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 招聘面试定向邀请实体：记录受邀邮箱、所属岗位、邮件发送及候选人参面关联
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "recruitment_invitations",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_recruitment_invitations_code", columnNames = "invite_code"),
        @UniqueConstraint(name = "uk_recruitment_invitations_job_email", columnNames = {"job_id", "email"})
    },
    indexes = {
        @Index(name = "idx_recruitment_invitations_email", columnList = "email"),
        @Index(name = "idx_recruitment_invitations_job_status", columnList = "job_id,status"),
        @Index(name = "idx_recruitment_invitations_application", columnList = "application_id")
    })
public class RecruitmentInvitationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "job_id", nullable = false)
  private Long jobId;

  /** 小写规范化后的候选人邮箱 */
  @Column(nullable = false, length = 255)
  private String email;

  @Column(name = "candidate_name", length = 64)
  private String candidateName;

  @Column(name = "invite_code", nullable = false, length = 64)
  private String inviteCode;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private InvitationRecordStatus status = InvitationRecordStatus.ACTIVE;

  /** 是否在候选人上传简历后跳过初筛直接进入在线面试 */
  @Column(name = "skip_screening", nullable = false)
  private boolean skipScreening = false;

  @Column(length = 500)
  private String note;

  @Column(name = "invited_by")
  private Long invitedBy;

  @Column(name = "invited_at", nullable = false)
  private LocalDateTime invitedAt;

  @Column(name = "expires_at")
  private LocalDateTime expiresAt;

  @Column(name = "revoked_at")
  private LocalDateTime revokedAt;

  @Column(name = "application_id")
  private Long applicationId;

  @Column(name = "last_email_log_id")
  private Long lastEmailLogId;

  @Column(name = "send_count", nullable = false)
  private int sendCount = 1;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    if (invitedAt == null) {
      invitedAt = now;
    }
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }
}
