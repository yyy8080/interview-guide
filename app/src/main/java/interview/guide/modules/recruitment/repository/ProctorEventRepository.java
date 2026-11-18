package interview.guide.modules.recruitment.repository;

import interview.guide.modules.recruitment.model.ProctorEventEntity;
import interview.guide.modules.recruitment.model.ProctorEventType;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ProctorEventRepository extends JpaRepository<ProctorEventEntity, Long> {

  List<ProctorEventEntity> findByApplicationIdOrderByOccurredAtAsc(Long applicationId);

  long countByApplicationId(Long applicationId);

  @Query("""
      SELECT e.eventType AS eventType, COUNT(e) AS total
      FROM ProctorEventEntity e
      WHERE e.applicationId = :applicationId
      GROUP BY e.eventType
      """)
  List<EventCount> countByType(@Param("applicationId") Long applicationId);

  interface EventCount {
    ProctorEventType getEventType();

    long getTotal();
  }
}
