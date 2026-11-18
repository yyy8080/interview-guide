package interview.guide.modules.job.repository;

import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.model.JobStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface JobPositionRepository extends JpaRepository<JobPositionEntity, Long> {

  @Query("""
      SELECT j FROM JobPositionEntity j
      WHERE LOWER(j.title) LIKE :pattern OR LOWER(COALESCE(j.department, '')) LIKE :pattern
      ORDER BY j.updatedAt DESC
      """)
  List<JobPositionEntity> searchAll(@Param("pattern") String pattern);

  @Query("""
      SELECT j FROM JobPositionEntity j
      WHERE j.status = :status
        AND (LOWER(j.title) LIKE :pattern OR LOWER(COALESCE(j.department, '')) LIKE :pattern)
      ORDER BY j.updatedAt DESC
      """)
  List<JobPositionEntity> searchByStatus(@Param("status") JobStatus status, @Param("pattern") String pattern);

  /** 候选人门户：招聘中且未过截止时间的岗位 */
  @Query("""
      SELECT j FROM JobPositionEntity j
      WHERE j.status = interview.guide.modules.job.model.JobStatus.OPEN
        AND (j.deadline IS NULL OR j.deadline > :now)
      ORDER BY j.publishedAt DESC, j.id DESC
      """)
  List<JobPositionEntity> findAcceptingApplications(@Param("now") LocalDateTime now);
}
