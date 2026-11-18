package interview.guide.modules.recruitment.service;

import interview.guide.modules.recruitment.model.AnswerTelemetryEntity;
import interview.guide.modules.recruitment.model.ProctorEventEntity;
import interview.guide.modules.recruitment.model.ProctorRiskAssessment;
import interview.guide.modules.recruitment.repository.AnswerTelemetryRepository;
import interview.guide.modules.recruitment.repository.ProctorEventRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 加载投递的行为事件与作答遥测并计算作弊风险
 */
@Service
@RequiredArgsConstructor
public class ProctorRiskService {

  private final ProctorEventRepository proctorEventRepository;
  private final AnswerTelemetryRepository answerTelemetryRepository;
  private final ProctorRiskCalculator calculator;

  public ProctorRiskAssessment assess(Long applicationId) {
    return assess(proctorEventRepository.findByApplicationIdOrderByOccurredAtAsc(applicationId),
        answerTelemetryRepository.findByApplicationIdOrderByQuestionIndexAsc(applicationId));
  }

  public ProctorRiskAssessment assess(List<ProctorEventEntity> events, List<AnswerTelemetryEntity> telemetry) {
    return calculator.assess(events, telemetry);
  }
}
