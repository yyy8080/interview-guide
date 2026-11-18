package interview.guide.modules.mail.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EmailTemplateRequest(
    @NotBlank(message = "邮件主题不能为空") @Size(max = 255) String subject,
    @NotBlank(message = "邮件正文不能为空") @Size(max = 20000) String body,
    boolean enabled
) {
}
