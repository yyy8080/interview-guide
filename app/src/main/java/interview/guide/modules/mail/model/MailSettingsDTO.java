package interview.guide.modules.mail.model;

import java.time.LocalDateTime;

/**
 * SMTP 配置视图（不返回密码，仅返回是否已设置）
 */
public record MailSettingsDTO(
    boolean enabled,
    String host,
    Integer port,
    String username,
    boolean passwordSet,
    MailSecurity security,
    String fromAddress,
    String fromName,
    String companyName,
    String portalBaseUrl,
    String contactInfo,
    LocalDateTime updatedAt
) {
}
