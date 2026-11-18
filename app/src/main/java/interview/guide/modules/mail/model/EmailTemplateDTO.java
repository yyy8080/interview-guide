package interview.guide.modules.mail.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 邮件模板视图；customized=false 表示使用内置默认内容
 */
public record EmailTemplateDTO(
    String code,
    String name,
    String subject,
    String body,
    boolean enabled,
    boolean customized,
    List<String> variables,
    LocalDateTime updatedAt
) {
}
