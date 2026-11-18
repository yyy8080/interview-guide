package interview.guide.modules.mail.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

/**
 * 保存邮件发信配置；password / wecomSecret 为空表示保持原密钥不变
 */
public record MailSettingsRequest(
    boolean enabled,
    MailChannel channel,
    @Size(max = 255) String host,
    @Min(1) @Max(65535) Integer port,
    @Size(max = 255) String username,
    @Size(max = 512) String password,
    MailSecurity security,
    @Email(message = "发件邮箱格式不正确") @Size(max = 255) String fromAddress,
    @Size(max = 128) String fromName,
    @Size(max = 128) String wecomCorpId,
    @Size(max = 512) String wecomSecret,
    @Size(max = 512) String wecomApiBaseUrl,
    @Size(max = 128) String companyName,
    @Size(max = 512) String portalBaseUrl,
    @Size(max = 512) String contactInfo
) {

  /** 兼容旧版仅传 SMTP 参数的调用 */
  public MailSettingsRequest(
      boolean enabled,
      String host,
      Integer port,
      String username,
      String password,
      MailSecurity security,
      String fromAddress,
      String fromName,
      String companyName,
      String portalBaseUrl,
      String contactInfo
  ) {
    this(enabled, MailChannel.SMTP, host, port, username, password, security, fromAddress,
        fromName, null, null, null, companyName, portalBaseUrl, contactInfo);
  }
}
