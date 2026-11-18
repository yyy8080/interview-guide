package interview.guide.modules.mail.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 保存 SMTP 配置；password 为空表示保持原密码不变
 */
public record MailSettingsRequest(
    boolean enabled,
    @Size(max = 255) String host,
    @Min(1) @Max(65535) Integer port,
    @Size(max = 255) String username,
    @Size(max = 512) String password,
    @NotNull(message = "加密方式不能为空") MailSecurity security,
    @Email(message = "发件邮箱格式不正确") @Size(max = 255) String fromAddress,
    @Size(max = 128) String fromName,
    @Size(max = 128) String companyName,
    @Size(max = 512) String portalBaseUrl,
    @Size(max = 512) String contactInfo
) {
}
