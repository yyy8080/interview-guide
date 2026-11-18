package interview.guide.modules.recruitment.repository;

import interview.guide.modules.recruitment.model.AnswerTelemetryEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AnswerTelemetryRepository extends JpaRepository<AnswerTelemetryEntity, Long> {

  List<AnswerTelemetryEntity> findByApplicationIdOrderByQuestionIndexAsc(Long applicationId);

  Optional<AnswerTelemetryEntity> findByApplicationIdAndQuestionIndex(Long applicationId, Integer questionIndex);
}
