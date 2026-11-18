package interview.guide.modules.mail.model;

/**
 * SMTP 连接加密方式
 */
public enum MailSecurity {
  /** 明文（仅限内网 / 本地 Mailpit） */
  NONE,
  /** SMTPS（通常 465 端口） */
  SSL,
  /** STARTTLS（通常 587 端口） */
  STARTTLS
}
