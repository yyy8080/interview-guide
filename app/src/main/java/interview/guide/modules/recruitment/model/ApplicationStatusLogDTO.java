package interview.guide.modules.recruitment.model;

import java.time.LocalDateTime;

public record ApplicationStatusLogDTO(
    Long id,
    ApplicationStatus fromStatus,
    ApplicationStatus toStatus,
    OperatorType operatorType,
    Long operatorId,
    String operatorName,
    String remark,
    LocalDateTime createdAt
) {
}
