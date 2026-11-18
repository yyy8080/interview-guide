package interview.guide.modules.recruitment.repository;

import interview.guide.modules.recruitment.model.ApplicationStatusLogEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ApplicationStatusLogRepository extends JpaRepository<ApplicationStatusLogEntity, Long> {

  List<ApplicationStatusLogEntity> findByApplicationIdOrderByCreatedAtAscIdAsc(Long applicationId);
}
