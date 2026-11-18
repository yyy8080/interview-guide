package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 投递单：一个候选人对一个岗位只能投递一次
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "applications")
public class ApplicationEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "job_id", nullable = false)
  private Long jobId;

  @Column(name = "candidate_id", nullable = false)
  private Long candidateId;

  @Column(name = "resume_id", nullable = false)
  private Long resumeId;

  /** 投递时填写的姓名快照 */
  @Column(name = "candidate_name", nullable = false, length = 64)
  private String candidateName;

  @Column(name = "candidate_phone", length = 32)
  private String candidatePhone;

  /** 候选人自选应聘定位（junior / mid / senior） */
  @Column(name = "candidate_level", length = 16)
  private String candidateLevel;

  /** 仅允许通过 ApplicationStateMachine 修改 */
  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ApplicationStatus status;

  /** 候选人实际选择的面试形式（TEXT / VOICE） */
  @Enumerated(EnumType.STRING)
  @Column(name = "interview_mode", length = 16)
  private InterviewMode interviewMode;

  /** 文字面试会话 ID（interview_sessions.session_id） */
  @Column(name = "interview_session_id", length = 64)
  private String interviewSessionId;

  /** 语音面试会话 ID（voice_interview_sessions.id） */
  @Column(name = "voice_session_id")
  private Long voiceSessionId;

  // ========== AI 初筛 ==========

  @Column(name = "screening_score")
  private Integer screeningScore;

  @Column(name = "screening_result_json", columnDefinition = "TEXT")
  private String screeningResultJson;

  /** 简历初筛未通过后候选人修改重传简历的次数 */
  @Column(name = "resume_revision_count", nullable = false)
  private int resumeRevisionCount = 0;

  // ========== AI 决策 ==========

  /** 面试评估总分 */
  @Column(name = "ai_score")
  private Integer aiScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "ai_decision", length = 16)
  private AiDecision aiDecision;

  @Column(name = "ai_result_json", columnDefinition = "TEXT")
  private String aiResultJson;

  // ========== 作答行为风险（交卷时计算，仅供参考） ==========

  @Column(name = "proctor_risk_score")
  private Integer proctorRiskScore;

  @Enumerated(EnumType.STRING)
  @Column(name = "proctor_risk_level", length = 16)
  private ProctorRiskLevel proctorRiskLevel;

  /** 在线笔试得分 0-100 */
  @Column(name = "written_score")
  private Integer writtenScore;

  // ========== 异步任务（初筛 / AI 决策）执行代次 ==========

  @Column(name = "task_attempt_id", length = 64)
  private String taskAttemptId;

  @Column(name = "task_updated_at")
  private LocalDateTime taskUpdatedAt;

  @Column(name = "task_retry_count", nullable = false)
  private int taskRetryCount = 0;

  @Column(name = "task_error", length = 500)
  private String taskError;

  // ========== HR 审核 ==========

  @Column(name = "hr_reviewer_id")
  private Long hrReviewerId;

  @Column(name = "hr_comment", columnDefinition = "TEXT")
  private String hrComment;

  @Column(name = "hr_reviewed_at")
  private LocalDateTime hrReviewedAt;

  // ========== 时间线 ==========

  /** 隐私授权时间 */
  @Column(name = "consent_at", nullable = false)
  private LocalDateTime consentAt;

  @Column(name = "submitted_at", nullable = false)
  private LocalDateTime submittedAt;

  @Column(name = "invited_at")
  private LocalDateTime invitedAt;

  /** 面试邀请过期时间，超时未开始面试转为 EXPIRED */
  @Column(name = "invite_expires_at")
  private LocalDateTime inviteExpiresAt;

  @Column(name = "interview_started_at")
  private LocalDateTime interviewStartedAt;

  /** 面试作答截止时间（开始时间 + 岗位时长），到时自动交卷 */
  @Column(name = "interview_deadline_at")
  private LocalDateTime interviewDeadlineAt;

  @Column(name = "interview_completed_at")
  private LocalDateTime interviewCompletedAt;

  @Column(name = "decided_at")
  private LocalDateTime decidedAt;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @Version
  @Column(nullable = false)
  private Long version = 0L;

  @PrePersist
  void prePersist() {
    LocalDateTime now = LocalDateTime.now();
    createdAt = now;
    updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = LocalDateTime.now();
  }

  /** 开启新一轮异步任务，返回新的执行代次 */
  public String beginTask(String attemptId) {
    this.taskAttemptId = attemptId;
    this.taskUpdatedAt = LocalDateTime.now();
    this.taskError = null;
    return attemptId;
  }

  /** 结束异步任务 */
  public void finishTask() {
    this.taskAttemptId = null;
    this.taskUpdatedAt = LocalDateTime.now();
    this.taskRetryCount = 0;
    this.taskError = null;
  }
}
