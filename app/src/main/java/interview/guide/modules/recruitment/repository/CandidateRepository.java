package interview.guide.modules.recruitment.repository;

import interview.guide.modules.recruitment.model.CandidateEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CandidateRepository extends JpaRepository<CandidateEntity, Long> {

  Optional<CandidateEntity> findByEmail(String email);
}
