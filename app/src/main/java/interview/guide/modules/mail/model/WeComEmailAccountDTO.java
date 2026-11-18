package interview.guide.modules.mail.model;

import java.util.List;

/**
 * 企业微信应用邮箱账号信息（对应 /cgi-bin/exmail/app/get_email_alias）
 */
public record WeComEmailAccountDTO(
    String email,
    List<String> aliasList
) {
}
