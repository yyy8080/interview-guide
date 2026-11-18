package interview.guide.modules.recruitment.repository;

import interview.guide.modules.recruitment.model.RecruitmentInvitationEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface RecruitmentInvitationRepository extends JpaRepository<RecruitmentInvitationEntity, Long> {

  Optional<RecruitmentInvitationEntity> findByJobIdAndEmail(Long jobId, String email);

  List<RecruitmentInvitationEntity> findByEmailOrderByInvitedAtDesc(String email);

  @Query("""
      SELECT i FROM RecruitmentInvitationEntity i
      WHERE (:jobId IS NULL OR i.jobId = :jobId)
        AND (LOWER(i.email) LIKE :pattern OR LOWER(COALESCE(i.candidateName, '')) LIKE :pattern)
      """)
  Page<RecruitmentInvitationEntity> search(@Param("jobId") Long jobId,
                                           @Param("pattern") String pattern,
                                           Pageable pageable);

  @Query("""
      SELECT i FROM RecruitmentInvitationEntity i
      WHERE (:jobId IS NULL OR i.jobId = :jobId)
        AND (LOWER(i.email) LIKE :pattern OR LOWER(COALESCE(i.candidateName, '')) LIKE :pattern)
      ORDER BY i.invitedAt DESC, i.id DESC
      """)
  List<RecruitmentInvitationEntity> searchAll(@Param("jobId") Long jobId,
                                              @Param("pattern") String pattern);
}
