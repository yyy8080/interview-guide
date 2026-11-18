package interview.guide.modules.job.model;

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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 招聘岗位实体：岗位展示信息 + 在线面试配置 + AI 审核阈值
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "job_positions", indexes = {
    @Index(name = "idx_job_positions_status_updated", columnList = "status,updated_at")
})
public class JobPositionEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  // ========== 岗位展示信息 ==========

  @Column(nullable = false, length = 128)
  private String title;

  @Column(length = 128)
  private String department;

  @Column(length = 128)
  private String location;

  @Enumerated(EnumType.STRING)
  @Column(name = "employment_type", nullable = false, length = 20)
  private EmploymentType employmentType = EmploymentType.FULL_TIME;

  private Integer headcount;

  @Column(name = "salary_range", length = 64)
  private String salaryRange;

  @Column(name = "jd_text", nullable = false, columnDefinition = "TEXT")
  private String jdText;

  // ========== 在线面试配置 ==========

  /** JD 解析出的考察维度 JSON（InterviewSkillService.CategoryDTO 列表） */
  @Column(name = "categories_json", columnDefinition = "TEXT")
  private String categoriesJson;

  /** 面试主题：预设 skill ID 或 custom */
  @Column(name = "skill_id", nullable = false, length = 64)
  private String skillId;

  @Column(nullable = false, length = 16)
  private String difficulty;

  @Column(name = "question_count", nullable = false)
  private Integer questionCount;

  @Enumerated(EnumType.STRING)
  @Column(name = "interview_mode", nullable = false, length = 16)
  private InterviewMode interviewMode = InterviewMode.BOTH;

  /** 可选：绑定知识库题库出题 */
  @Column(name = "knowledge_base_id")
  private Long knowledgeBaseId;

  /** 面试总时长限制（分钟），为空表示不限时 */
  @Column(name = "time_limit_minutes")
  private Integer timeLimitMinutes;

  @Column(name = "llm_provider", length = 50)
  private String llmProvider;

  // ========== AI 审核配置 ==========

  @Column(name = "screening_enabled", nullable = false)
  private boolean screeningEnabled = true;

  @Column(name = "screening_threshold")
  private Integer screeningThreshold;

  @Column(name = "ai_pass_threshold", nullable = false)
  private Integer aiPassThreshold;

  // ========== 防作弊配置（低打扰） ==========

  /** 候选人长时间离开页面后回来时给出柔和提醒 */
  @Column(name = "proctor_away_reminder", nullable = false)
  private boolean proctorAwayReminder = true;

  /** 在线面试页面显示极淡的候选人信息水印 */
  @Column(name = "proctor_watermark", nullable = false)
  private boolean proctorWatermark = true;

  // ========== 在线笔试配置（选择题 / 填空题） ==========

  /** 是否在 AI 面试前进行在线笔试 */
  @Column(name = "written_test_enabled", nullable = false)
  private boolean writtenTestEnabled = false;

  @Column(name = "written_single_count", nullable = false)
  private int writtenSingleCount = 5;

  @Column(name = "written_multi_count", nullable = false)
  private int writtenMultiCount = 3;

  @Column(name = "written_fill_count", nullable = false)
  private int writtenFillCount = 2;

  @Column(name = "written_time_limit_minutes", nullable = false)
  private int writtenTimeLimitMinutes = 15;

  /** 笔试成绩在综合分中的权重（%） */
  @Column(name = "written_weight", nullable = false)
  private int writtenWeight = 30;

  // ========== 状态与审计 ==========

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private JobStatus status = JobStatus.DRAFT;

  private LocalDateTime deadline;

  @Column(name = "published_at")
  private LocalDateTime publishedAt;

  @Column(name = "created_by")
  private Long createdBy;

  @Column(name = "created_at", nullable = false, updatable = false)
  private LocalDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

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

  /** 当前是否接受投递 */
  public boolean isAcceptingApplications(LocalDateTime now) {
    return status == JobStatus.OPEN && (deadline == null || deadline.isAfter(now));
  }
}
