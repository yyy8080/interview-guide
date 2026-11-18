package interview.guide.modules.recruitment.repository;

import interview.guide.modules.recruitment.model.WrittenTestEntity;
import interview.guide.modules.recruitment.model.WrittenTestStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface WrittenTestRepository extends JpaRepository<WrittenTestEntity, Long> {

  Optional<WrittenTestEntity> findByApplicationId(Long applicationId);

  List<WrittenTestEntity> findByApplicationIdIn(Collection<Long> applicationIds);

  boolean existsByApplicationIdAndStatus(Long applicationId, WrittenTestStatus status);

  /** 超过截止时间仍未交卷的笔试 */
  @Query("""
      SELECT w.applicationId FROM WrittenTestEntity w
      WHERE w.status = interview.guide.modules.recruitment.model.WrittenTestStatus.IN_PROGRESS
        AND w.deadlineAt < :before
      ORDER BY w.id
      """)
  List<Long> findOverdueApplicationIds(@Param("before") LocalDateTime before, Pageable pageable);

  /** 仅在作答中时保存答案（交卷后的迟到请求不会覆盖） */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE WrittenTestEntity w SET w.answersJson = :answers, w.updatedAt = :now
      WHERE w.id = :id AND w.status = interview.guide.modules.recruitment.model.WrittenTestStatus.IN_PROGRESS
      """)
  int saveAnswersIfInProgress(@Param("id") Long id, @Param("answers") String answers, @Param("now") LocalDateTime now);

  /** 条件交卷：作答中 → 已交卷（并发安全，只有一个请求能成功） */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE WrittenTestEntity w
      SET w.status = interview.guide.modules.recruitment.model.WrittenTestStatus.SUBMITTED,
          w.submittedAt = :now, w.updatedAt = :now
      WHERE w.id = :id AND w.status = interview.guide.modules.recruitment.model.WrittenTestStatus.IN_PROGRESS
      """)
  int markSubmitted(@Param("id") Long id, @Param("now") LocalDateTime now);
}
