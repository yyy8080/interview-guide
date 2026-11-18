package interview.guide.modules.mail.model;

import java.time.LocalDateTime;

public record EmailLogDTO(
    Long id,
    Long applicationId,
    String toAddress,
    String templateCode,
    String subject,
    EmailLogStatus status,
    String error,
    int retryCount,
    LocalDateTime sentAt,
    LocalDateTime createdAt
) {
}
