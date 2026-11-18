package interview.guide.modules.mail.model;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record TestMailRequest(
    @NotBlank(message = "收件邮箱不能为空") @Email(message = "收件邮箱格式不正确") String to
) {
}
