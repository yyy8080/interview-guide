package interview.guide.modules.mail.service;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.modules.mail.model.MailChannel;
import interview.guide.modules.mail.model.MailSecurity;
import interview.guide.modules.mail.model.MailSettingsDTO;
import interview.guide.modules.mail.model.MailSettingsEntity;
import interview.guide.modules.mail.model.MailSettingsRequest;
import interview.guide.modules.mail.repository.MailSettingsRepository;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台邮件配置：数据库单行存储，SMTP 密码与企业微信 Secret 均复用 AES-GCM 加密
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailSettingsService {

  private static final int TIMEOUT_MS = 15_000;

  private final MailSettingsRepository repository;
  private final ApiKeyEncryptionService encryptionService;
  private final RecruitmentProperties recruitmentProperties;

  @Transactional(readOnly = true)
  public MailSettingsDTO get() {
    MailSettingsEntity entity = current();
    return new MailSettingsDTO(
        entity.isEnabled(),
        entity.getChannel() != null ? entity.getChannel() : MailChannel.SMTP,
        entity.getHost(),
        entity.getPort(),
        entity.getUsername(),
        entity.hasPassword(),
        entity.getSecurity() != null ? entity.getSecurity() : MailSecurity.SSL,
        entity.getFromAddress(),
        entity.getFromName(),
        entity.getWecomCorpId(),
        entity.hasWecomSecret(),
        entity.getWecomApiBaseUrl(),
        entity.getCompanyName(),
        entity.getPortalBaseUrl(),
        entity.getContactInfo(),
        entity.getUpdatedAt());
  }

  @Transactional(rollbackFor = Exception.class)
  public MailSettingsDTO update(MailSettingsRequest request, Long operatorId) {
    MailSettingsEntity entity = repository.findById(MailSettingsEntity.SINGLETON_ID)
        .orElseGet(MailSettingsEntity::new);
    MailChannel channel = request.channel() != null ? request.channel() : MailChannel.SMTP;
    MailSecurity security = request.security() != null
        ? request.security()
        : (entity.getSecurity() != null ? entity.getSecurity() : MailSecurity.SSL);

    entity.setEnabled(request.enabled());
    entity.setChannel(channel);
    entity.setHost(trimToNull(request.host()));
    entity.setPort(request.port());
    entity.setUsername(trimToNull(request.username()));
    entity.setSecurity(security);
    entity.setFromAddress(trimToNull(request.fromAddress()));
    entity.setFromName(trimToNull(request.fromName()));
    entity.setWecomCorpId(trimToNull(request.wecomCorpId()));
    String wecomBaseUrl = normalizeUrl(request.wecomApiBaseUrl());
    if (wecomBaseUrl != null && !wecomBaseUrl.startsWith("https://") && !wecomBaseUrl.startsWith("http://")) {
      throw new BusinessException(ErrorCode.MAIL_CONFIG_INVALID, "企业微信 API 地址必须以 https:// 或 http:// 开头");
    }
    entity.setWecomApiBaseUrl(wecomBaseUrl);
    entity.setCompanyName(trimToNull(request.companyName()));
    entity.setPortalBaseUrl(normalizeUrl(request.portalBaseUrl()));
    entity.setContactInfo(trimToNull(request.contactInfo()));

    if (request.password() != null && !request.password().isBlank()) {
      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(request.password());
      entity.setPasswordCiphertext(encrypted.ciphertext());
      entity.setPasswordNonce(encrypted.nonce());
    }
    if (request.wecomSecret() != null && !request.wecomSecret().isBlank()) {
      ApiKeyEncryptionService.EncryptedValue encrypted = encryptionService.encrypt(request.wecomSecret().trim());
      entity.setWecomSecretCiphertext(encrypted.ciphertext());
      entity.setWecomSecretNonce(encrypted.nonce());
    }
    if (entity.isEnabled()) {
      validateForSending(entity);
    }
    entity.setUpdatedBy(operatorId);
    repository.save(entity);
    log.info("邮件发送配置已更新: enabled={}, channel={}, host={}, wecomCorpId={}, operatorId={}",
        entity.isEnabled(), entity.getChannel(), entity.getHost(), entity.getWecomCorpId(), operatorId);
    return get();
  }

  /**
   * 读取当前生效配置（含解密后的密钥），仅供发信与连通性校验使用
   */
  @Transactional(readOnly = true)
  public ResolvedMailConfig resolve() {
    MailSettingsEntity entity = current();
    String password = null;
    if (entity.hasPassword()) {
      password = encryptionService.decrypt(entity.getPasswordNonce(), entity.getPasswordCiphertext());
    }
    String wecomSecret = null;
    if (entity.hasWecomSecret()) {
      wecomSecret = encryptionService.decrypt(entity.getWecomSecretNonce(), entity.getWecomSecretCiphertext());
    }
    String companyName = entity.getCompanyName() != null
        ? entity.getCompanyName() : recruitmentProperties.getCompanyName();
    String portalBaseUrl = entity.getPortalBaseUrl() != null
        ? entity.getPortalBaseUrl() : normalizeUrl(recruitmentProperties.getPortalBaseUrl());
    MailChannel channel = entity.getChannel() != null ? entity.getChannel() : MailChannel.SMTP;
    return new ResolvedMailConfig(
        entity.isEnabled(), channel, entity.getHost(), entity.getPort(), entity.getUsername(), password,
        entity.getSecurity(), entity.getFromAddress(), entity.getFromName(),
        entity.getWecomCorpId(), wecomSecret, entity.getWecomApiBaseUrl(),
        companyName, portalBaseUrl, entity.getContactInfo() == null ? "" : entity.getContactInfo());
  }

  /**
   * 按配置构建 JavaMailSender；未启用或配置不完整时抛出业务异常
   */
  public JavaMailSenderImpl buildSender(ResolvedMailConfig config) {
    if (!config.enabled()) {
      throw new BusinessException(ErrorCode.MAIL_NOT_CONFIGURED);
    }
    if (isBlank(config.host()) || config.port() == null || isBlank(config.fromAddress())) {
      throw new BusinessException(ErrorCode.MAIL_CONFIG_INVALID, "SMTP 主机、端口与发件邮箱不能为空");
    }
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(config.host());
    sender.setPort(config.port());
    sender.setDefaultEncoding(StandardCharsets.UTF_8.name());
    boolean auth = !isBlank(config.username());
    if (auth) {
      sender.setUsername(config.username());
      sender.setPassword(config.password());
    }
    Properties props = sender.getJavaMailProperties();
    props.put("mail.smtp.auth", String.valueOf(auth));
    props.put("mail.smtp.connectiontimeout", String.valueOf(TIMEOUT_MS));
    props.put("mail.smtp.timeout", String.valueOf(TIMEOUT_MS));
    props.put("mail.smtp.writetimeout", String.valueOf(TIMEOUT_MS));
    MailSecurity security = config.security() == null ? MailSecurity.SSL : config.security();
    switch (security) {
      case SSL -> {
        props.put("mail.smtp.ssl.enable", "true");
        props.put("mail.smtp.ssl.checkserveridentity", "true");
      }
      case STARTTLS -> {
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
      }
      case NONE -> {
        // 明文连接，仅用于本地 / 内网 SMTP
      }
    }
    return sender;
  }

  private MailSettingsEntity current() {
    return repository.findById(MailSettingsEntity.SINGLETON_ID).orElseGet(MailSettingsEntity::new);
  }

  private void validateForSending(MailSettingsEntity entity) {
    MailChannel channel = entity.getChannel() != null ? entity.getChannel() : MailChannel.SMTP;
    if (channel == MailChannel.WECOM_API) {
      if (isBlank(entity.getWecomCorpId()) || !entity.hasWecomSecret()) {
        throw new BusinessException(ErrorCode.MAIL_CONFIG_INVALID,
            "启用企业微信邮件发送需填写企业 ID (CorpID) 与邮件应用 Secret");
      }
      return;
    }
    if (isBlank(entity.getHost()) || entity.getPort() == null || isBlank(entity.getFromAddress())) {
      throw new BusinessException(ErrorCode.MAIL_CONFIG_INVALID, "启用邮件服务需填写 SMTP 主机、端口与发件邮箱");
    }
    if (!isBlank(entity.getUsername()) && !entity.hasPassword()) {
      throw new BusinessException(ErrorCode.MAIL_CONFIG_INVALID, "已填写 SMTP 用户名，请同时设置密码 / 授权码");
    }
  }

  static String normalizeUrl(String url) {
    String trimmed = trimToNull(url);
    if (trimmed == null) {
      return null;
    }
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  /**
   * 生效的邮件配置（密钥已解密，禁止返回给前端或写日志）
   */
  public record ResolvedMailConfig(
      boolean enabled,
      MailChannel channel,
      String host,
      Integer port,
      String username,
      String password,
      MailSecurity security,
      String fromAddress,
      String fromName,
      String wecomCorpId,
      String wecomSecret,
      String wecomApiBaseUrl,
      String companyName,
      String portalBaseUrl,
      String contactInfo
  ) {

    /** 兼容旧版 SMTP 构造参数 */
    public ResolvedMailConfig(
        boolean enabled,
        String host,
        Integer port,
        String username,
        String password,
        MailSecurity security,
        String fromAddress,
        String fromName,
        String companyName,
        String portalBaseUrl,
        String contactInfo
    ) {
      this(enabled, MailChannel.SMTP, host, port, username, password, security, fromAddress,
          fromName, null, null, null, companyName, portalBaseUrl, contactInfo);
    }

    @Override
    public String toString() {
      return "ResolvedMailConfig[channel=" + channel + ", host=" + host + ", port=" + port
          + ", wecomCorpId=" + wecomCorpId + ", enabled=" + enabled + "]";
    }
  }
}
