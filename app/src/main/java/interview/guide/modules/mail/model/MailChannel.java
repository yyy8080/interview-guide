package interview.guide.modules.mail.model;

/**
 * 邮件发送渠道
 */
public enum MailChannel {

  /** 标准 SMTP 协议发信 */
  SMTP,

  /** 企业微信邮件 API（/cgi-bin/exmail/app/compose_send） */
  WECOM_API
}
