package interview.guide.modules.mail.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.mail.model.MailChannel;
import interview.guide.modules.mail.model.WeComEmailAccountDTO;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import jakarta.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * 底层邮件投递：按当前通道（SMTP 或企业微信邮件 API）发送邮件（不涉及数据库事务）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailDeliveryService {

  private final MailSettingsService settingsService;
  private final WeComMailClient weComMailClient;

  /**
   * 同步发送邮件
   *
   * @param ics 可选的 iCalendar 附件
   * @throws BusinessException MAIL_NOT_CONFIGURED / MAIL_CONFIG_INVALID / MAIL_SEND_FAILED
   */
  public void deliver(ResolvedMailConfig config, String to, String subject, String body, String ics) {
    if (!config.enabled()) {
      throw new BusinessException(ErrorCode.MAIL_NOT_CONFIGURED);
    }
    MailChannel channel = config.channel() != null ? config.channel() : MailChannel.SMTP;
    if (channel == MailChannel.WECOM_API) {
      weComMailClient.sendMail(config, to, subject, body, ics);
      return;
    }

    JavaMailSenderImpl sender = settingsService.buildSender(config);
    try {
      MimeMessage message = sender.createMimeMessage();
      boolean multipart = ics != null && !ics.isBlank();
      MimeMessageHelper helper = new MimeMessageHelper(message, multipart, StandardCharsets.UTF_8.name());
      if (config.fromName() != null && !config.fromName().isBlank()) {
        helper.setFrom(config.fromAddress(), config.fromName());
      } else {
        helper.setFrom(config.fromAddress());
      }
      helper.setTo(to);
      helper.setSubject(subject);
      helper.setText(body, false);
      if (multipart) {
        helper.addAttachment("invite.ics",
            new ByteArrayResource(ics.getBytes(StandardCharsets.UTF_8)), "text/calendar; charset=UTF-8; method=REQUEST");
      }
      sender.send(message);
    } catch (BusinessException e) {
      throw e;
    } catch (UnsupportedEncodingException | jakarta.mail.MessagingException
             | org.springframework.mail.MailException e) {
      log.warn("邮件发送失败: to={}, error={}", maskEmail(to), e.getMessage());
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED, "邮件发送失败: " + summarize(e));
    }
  }

  /**
   * 查询当前已保存的企业微信应用发信主邮箱与别名（外部 HTTP 在事务外执行）
   */
  public WeComEmailAccountDTO queryWeComAccount() {
    ResolvedMailConfig config = settingsService.resolve();
    return weComMailClient.queryAppEmailAccount(config);
  }

  static String summarize(Exception e) {
    String message = e.getMessage();
    if (message == null) {
      return e.getClass().getSimpleName();
    }
    return message.length() > 200 ? message.substring(0, 200) : message;
  }

  public static String maskEmail(String email) {
    if (email == null) {
      return null;
    }
    int at = email.indexOf('@');
    if (at <= 1) {
      return "***" + (at >= 0 ? email.substring(at) : "");
    }
    return email.charAt(0) + "***" + email.substring(at);
  }
}
