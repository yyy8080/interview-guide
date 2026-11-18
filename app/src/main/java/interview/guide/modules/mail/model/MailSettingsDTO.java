package interview.guide.modules.mail.model;

import java.time.LocalDateTime;

/**
 * 邮件发信配置视图（不返回明文密码或 Secret，仅返回是否已设置）
 */
public record MailSettingsDTO(
    boolean enabled,
    MailChannel channel,
    String host,
    Integer port,
    String username,
    boolean passwordSet,
    MailSecurity security,
    String fromAddress,
    String fromName,
    String wecomCorpId,
    boolean wecomSecretSet,
    String wecomApiBaseUrl,
    String companyName,
    String portalBaseUrl,
    String contactInfo,
    LocalDateTime updatedAt
) {
}
