package interview.guide.modules.mail.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 发件箱补偿调度：投递到期的待发送 / 重试邮件，恢复发送中卡死的记录
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MailDispatchScheduler {

  private static final int BATCH_SIZE = 20;

  private final MailOutboxService outboxService;

  @Scheduled(fixedDelayString = "${app.recruitment.mail-dispatch-interval-ms:30000}", initialDelay = 20000)
  public void dispatchDue() {
    try {
      int count = outboxService.dispatchDue(BATCH_SIZE);
      if (count > 0) {
        log.info("邮件发件箱补偿投递: {} 封", count);
      }
    } catch (Exception e) {
      log.warn("邮件发件箱补偿投递异常: {}", e.getMessage());
    }
  }
}
