package interview.guide.modules.mail.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.PageResult;
import interview.guide.modules.mail.model.EmailLogDTO;
import interview.guide.modules.mail.model.EmailLogEntity;
import interview.guide.modules.mail.model.EmailLogStatus;
import interview.guide.modules.mail.model.EmailTemplateCode;
import interview.guide.modules.mail.repository.EmailLogRepository;
import interview.guide.modules.mail.service.EmailTemplateService.RenderedMail;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 邮件发件箱（Outbox）。
 * <ul>
 *   <li>业务事务内调用 {@link #enqueue} 写入 PENDING 记录，事务提交后异步投递</li>
 *   <li>投递失败按退避策略重试，超过上限标记 FAILED，后台可手动重发</li>
 *   <li>验证码邮件同步发送（{@link #sendVerificationCode}），正文不留存</li>
 * </ul>
 */
@Slf4j
@Service
public class MailOutboxService {

  static final int MAX_RETRY = 5;
  private static final int[] BACKOFF_MINUTES = {1, 5, 15, 60, 180};
  private static final String MASKED_BODY = "（验证码邮件正文不留存）";

  private final EmailLogRepository logRepository;
  private final EmailTemplateService templateService;
  private final MailSettingsService settingsService;
  private final MailDeliveryService deliveryService;
  private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
    Thread thread = new Thread(runnable, "mail-dispatch");
    thread.setDaemon(true);
    return thread;
  });

  public MailOutboxService(EmailLogRepository logRepository, EmailTemplateService templateService,
                           MailSettingsService settingsService, MailDeliveryService deliveryService) {
    this.logRepository = logRepository;
    this.templateService = templateService;
    this.settingsService = settingsService;
    this.deliveryService = deliveryService;
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }

  /**
   * 写入待发送邮件（加入调用方事务），事务提交后异步投递。模板停用时跳过。
   *
   * @return 发件箱记录 ID；模板停用时为空
   */
  @Transactional(rollbackFor = Exception.class)
  public Optional<Long> enqueue(Long applicationId, String to, EmailTemplateCode code,
                                Map<String, String> variables, String attachmentIcs) {
    ResolvedMailConfig config = settingsService.resolve();
    RenderedMail mail = templateService.render(code, withCommonVariables(config, variables));
    if (!mail.enabled()) {
      log.info("邮件模板已停用，跳过发送: code={}, applicationId={}", code, applicationId);
      return Optional.empty();
    }
    EmailLogEntity entity = new EmailLogEntity();
    entity.setApplicationId(applicationId);
    entity.setToAddress(to);
    entity.setTemplateCode(code.name());
    entity.setSubject(truncate(mail.subject(), 255));
    entity.setBody(mail.body());
    entity.setAttachmentIcs(attachmentIcs);
    entity.setStatus(EmailLogStatus.PENDING);
    entity.setNextAttemptAt(LocalDateTime.now());
    EmailLogEntity saved = logRepository.save(entity);
    Long id = saved.getId();
    runAfterCommit(() -> dispatchAsync(id));
    return Optional.of(id);
  }

  /**
   * 同步发送验证码邮件；邮件服务未启用时抛出 MAIL_NOT_CONFIGURED
   */
  public void sendVerificationCode(String to, String code, long ttlMinutes) {
    ResolvedMailConfig config = settingsService.resolve();
    Map<String, String> variables = new HashMap<>();
    variables.put("code", code);
    variables.put("ttlMinutes", String.valueOf(ttlMinutes));
    RenderedMail mail = templateService.render(EmailTemplateCode.VERIFICATION_CODE,
        withCommonVariables(config, variables));
    EmailLogEntity entity = new EmailLogEntity();
    entity.setToAddress(to);
    entity.setTemplateCode(EmailTemplateCode.VERIFICATION_CODE.name());
    entity.setSubject(truncate(mail.subject(), 255));
    entity.setBody(MASKED_BODY);
    try {
      deliveryService.deliver(config, to, mail.subject(), mail.body(), null);
      entity.setStatus(EmailLogStatus.SENT);
      entity.setSentAt(LocalDateTime.now());
      saveQuietly(entity);
    } catch (BusinessException e) {
      entity.setStatus(EmailLogStatus.FAILED);
      entity.setError(truncate(e.getMessage(), 500));
      saveQuietly(entity);
      throw e;
    }
  }

  /** 发送测试邮件（同步，直接返回错误信息） */
  public void sendTest(String to) {
    ResolvedMailConfig config = settingsService.resolve();
    deliveryService.deliver(config, to, "【" + config.companyName() + "】SMTP 测试邮件",
        "这是一封测试邮件，收到说明招聘系统的 SMTP 配置正确。\n\n发送时间：" + LocalDateTime.now(), null);
  }

  /** 投递到期的待发送邮件（调度器调用） */
  public int dispatchDue(int limit) {
    logRepository.releaseStuck(LocalDateTime.now().minusMinutes(10));
    List<Long> ids = logRepository.findDueIds(LocalDateTime.now(), PageRequest.of(0, limit));
    ids.forEach(this::dispatch);
    return ids.size();
  }

  /**
   * 领取并投递一封邮件；领取失败（已被其他实例处理）直接返回
   */
  public void dispatch(Long id) {
    if (logRepository.claim(id, LocalDateTime.now()) != 1) {
      return;
    }
    EmailLogEntity entity = logRepository.findById(id).orElse(null);
    if (entity == null) {
      return;
    }
    try {
      ResolvedMailConfig config = settingsService.resolve();
      deliveryService.deliver(config, entity.getToAddress(), entity.getSubject(), entity.getBody(),
          entity.getAttachmentIcs());
      entity.setStatus(EmailLogStatus.SENT);
      entity.setSentAt(LocalDateTime.now());
      entity.setError(null);
      logRepository.save(entity);
      log.info("邮件已发送: id={}, template={}, to={}", id, entity.getTemplateCode(),
          MailDeliveryService.maskEmail(entity.getToAddress()));
    } catch (Exception e) {
      int retry = entity.getRetryCount() + 1;
      entity.setRetryCount(retry);
      entity.setError(truncate(e.getMessage(), 500));
      if (retry >= MAX_RETRY) {
        entity.setStatus(EmailLogStatus.FAILED);
        entity.setNextAttemptAt(null);
        log.error("邮件发送最终失败: id={}, template={}, error={}", id, entity.getTemplateCode(), e.getMessage());
      } else {
        entity.setStatus(EmailLogStatus.PENDING);
        entity.setNextAttemptAt(LocalDateTime.now().plusMinutes(BACKOFF_MINUTES[retry - 1]));
        log.warn("邮件发送失败，稍后重试: id={}, retry={}, error={}", id, retry, e.getMessage());
      }
      logRepository.save(entity);
    }
  }

  /** 后台手动重发 */
  @Transactional(rollbackFor = Exception.class)
  public void resend(Long id) {
    EmailLogEntity entity = logRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "邮件记录不存在"));
    if (EmailTemplateCode.VERIFICATION_CODE.name().equals(entity.getTemplateCode())) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "验证码邮件不支持重发");
    }
    if (entity.getStatus() == EmailLogStatus.SENDING) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "邮件正在发送中");
    }
    entity.setStatus(EmailLogStatus.PENDING);
    entity.setRetryCount(0);
    entity.setError(null);
    entity.setNextAttemptAt(LocalDateTime.now());
    logRepository.save(entity);
    runAfterCommit(() -> dispatchAsync(id));
  }

  @Transactional(readOnly = true)
  public PageResult<EmailLogDTO> listLogs(EmailLogStatus status, int page, int size) {
    PageRequest pageable = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100));
    Page<EmailLogEntity> result = status == null
        ? logRepository.findAllByOrderByIdDesc(pageable)
        : logRepository.findByStatusOrderByIdDesc(status, pageable);
    return PageResult.of(result, MailOutboxService::toDTO);
  }

  @Transactional(readOnly = true)
  public List<EmailLogDTO> listByApplication(Long applicationId) {
    return logRepository.findByApplicationIdOrderByIdDesc(applicationId).stream()
        .map(MailOutboxService::toDTO).toList();
  }

  static EmailLogDTO toDTO(EmailLogEntity e) {
    return new EmailLogDTO(e.getId(), e.getApplicationId(), e.getToAddress(), e.getTemplateCode(),
        e.getSubject(), e.getStatus(), e.getError(), e.getRetryCount(), e.getSentAt(), e.getCreatedAt());
  }

  private Map<String, String> withCommonVariables(ResolvedMailConfig config, Map<String, String> variables) {
    Map<String, String> merged = new HashMap<>();
    merged.put("companyName", config.companyName());
    merged.put("contactInfo", config.contactInfo());
    merged.put("portalUrl", config.portalBaseUrl());
    if (variables != null) {
      variables.forEach((key, value) -> merged.put(key, value == null ? "" : value));
    }
    return merged;
  }

  private void dispatchAsync(Long id) {
    try {
      executor.submit(() -> {
        try {
          dispatch(id);
        } catch (Exception e) {
          log.error("异步投递邮件异常: id={}", id, e);
        }
      });
    } catch (RejectedExecutionException e) {
      log.warn("邮件投递线程池不可用，等待调度器补偿: id={}", id);
    }
  }

  private void saveQuietly(EmailLogEntity entity) {
    try {
      logRepository.save(entity);
    } catch (Exception e) {
      log.warn("记录邮件日志失败: {}", e.getMessage());
    }
  }

  private static void runAfterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      action.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
      @Override
      public void afterCommit() {
        action.run();
      }
    });
  }

  private static String truncate(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }
}
