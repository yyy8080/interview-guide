package interview.guide.modules.recruitment.model;

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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 候选人在线笔试（每个投递一份）
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "written_tests")
public class WrittenTestEntity {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(name = "application_id", nullable = false, unique = true)
  private Long applicationId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 16)
  private WrittenTestStatus status;

  /** 题目（含标准答案） */
  @Column(name = "questions_json", nullable = false, columnDefinition = "TEXT")
  private String questionsJson;

  /** 候选人作答：题号 -> 选项字母 / 填空内容 */
  @Column(name = "answers_json", columnDefinition = "TEXT")
  private String answersJson;

  /** 逐题判分结果 */
  @Column(name = "results_json", columnDefinition = "TEXT")
  private String resultsJson;

  @Column(name = "score")
  private Integer score;

  @Column(name = "correct_count")
  private Integer correctCount;

  @Column(name = "total_count", nullable = false)
  private Integer totalCount;

  @Column(name = "started_at", nullable = false)
  private LocalDateTime startedAt;

  @Column(name = "deadline_at", nullable = false)
  private LocalDateTime deadlineAt;

  @Column(name = "submitted_at")
  private LocalDateTime submittedAt;

  @Column(name = "graded_at")
  private LocalDateTime gradedAt;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  @PreUpdate
  void touch() {
    updatedAt = LocalDateTime.now();
  }
}
