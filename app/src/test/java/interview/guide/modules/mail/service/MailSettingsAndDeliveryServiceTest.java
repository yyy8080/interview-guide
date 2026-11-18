package interview.guide.modules.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService;
import interview.guide.modules.llmprovider.service.ApiKeyEncryptionService.EncryptedValue;
import interview.guide.modules.mail.model.MailChannel;
import interview.guide.modules.mail.model.MailSecurity;
import interview.guide.modules.mail.model.MailSettingsDTO;
import interview.guide.modules.mail.model.MailSettingsEntity;
import interview.guide.modules.mail.model.MailSettingsRequest;
import interview.guide.modules.mail.repository.MailSettingsRepository;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("邮件配置与双通道投递服务测试")
class MailSettingsAndDeliveryServiceTest {

  @Mock
  private MailSettingsRepository repository;

  @Mock
  private ApiKeyEncryptionService encryptionService;

  @Mock
  private WeComMailClient weComMailClient;

  private MailSettingsService settingsService;
  private MailDeliveryService deliveryService;

  @BeforeEach
  void setUp() {
    RecruitmentProperties props = new RecruitmentProperties();
    props.setCompanyName("默认科技");
    props.setPortalBaseUrl("https://careers.example.com/");
    settingsService = new MailSettingsService(repository, encryptionService, props);
    deliveryService = new MailDeliveryService(settingsService, weComMailClient);
  }

  @Test
  @DisplayName("保存企业微信 API 配置时加密 Secret，再次保存留空 Secret 时保留原密文且不校验必填 SMTP 主机")
  void encryptsAndRetainsWeComSecretWithoutRequiringSmtpFields() {
    AtomicReference<MailSettingsEntity> store = new AtomicReference<>(new MailSettingsEntity());
    when(repository.findById(MailSettingsEntity.SINGLETON_ID))
        .thenAnswer(inv -> Optional.ofNullable(store.get()));
    when(repository.save(any(MailSettingsEntity.class))).thenAnswer(inv -> {
      MailSettingsEntity saved = inv.getArgument(0);
      store.set(saved);
      return saved;
    });
    when(encryptionService.encrypt("wecom-app-secret"))
        .thenReturn(new EncryptedValue("nonce-sec-1", "cipher-sec-1"));
    when(encryptionService.decrypt("nonce-sec-1", "cipher-sec-1"))
        .thenReturn("wecom-app-secret");

    MailSettingsDTO firstSave = settingsService.update(new MailSettingsRequest(
        true,
        MailChannel.WECOM_API,
        null,
        null,
        null,
        null,
        null,
        null,
        "星辰招聘",
        "ww1234567890",
        "wecom-app-secret",
        "https://qyapi.weixin.qq.com/",
        "星辰科技",
        "https://jobs.star.com/",
        "hr@star.com"
    ), 10L);

    assertThat(firstSave.enabled()).isTrue();
    assertThat(firstSave.channel()).isEqualTo(MailChannel.WECOM_API);
    assertThat(firstSave.wecomCorpId()).isEqualTo("ww1234567890");
    assertThat(firstSave.wecomSecretSet()).isTrue();
    assertThat(firstSave.wecomApiBaseUrl()).isEqualTo("https://qyapi.weixin.qq.com");

    // 第二次更新留空 wecomSecret，保持已有加密密钥不变
    MailSettingsDTO secondSave = settingsService.update(new MailSettingsRequest(
        true,
        MailChannel.WECOM_API,
        null,
        null,
        null,
        null,
        MailSecurity.SSL,
        "recruit@star.wecom.work",
        "星辰招聘团队",
        "ww1234567890",
        "",
        null,
        "星辰科技",
        "https://jobs.star.com",
        "hr@star.com"
    ), 10L);

    assertThat(secondSave.wecomSecretSet()).isTrue();

    ResolvedMailConfig resolved = settingsService.resolve();
    assertThat(resolved.channel()).isEqualTo(MailChannel.WECOM_API);
    assertThat(resolved.wecomCorpId()).isEqualTo("ww1234567890");
    assertThat(resolved.wecomSecret()).isEqualTo("wecom-app-secret");
    assertThat(resolved.toString()).doesNotContain("wecom-app-secret");
  }

  @Test
  @DisplayName("启用企业微信 API 但缺少 CorpID 或 Secret 时抛出 MAIL_CONFIG_INVALID")
  void rejectsIncompleteWeComSettingsWhenEnabled() {
    when(repository.findById(MailSettingsEntity.SINGLETON_ID)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> settingsService.update(new MailSettingsRequest(
        true,
        MailChannel.WECOM_API,
        null,
        null,
        null,
        null,
        MailSecurity.SSL,
        null,
        null,
        "ww1234567890",
        null,
        null,
        "星辰科技",
        null,
        null
    ), 1L))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.MAIL_CONFIG_INVALID.getCode());
  }

  @Test
  @DisplayName("MailDeliveryService 在 WECOM_API 通道下委托 WeComMailClient 发送且不构建 SMTP Sender")
  void dispatchesWeComChannelToWeComMailClient() {
    ResolvedMailConfig config = new ResolvedMailConfig(
        true,
        MailChannel.WECOM_API,
        null,
        null,
        null,
        null,
        MailSecurity.SSL,
        null,
        "星辰招聘",
        "ww1234567890",
        "sec-xyz",
        null,
        "星辰科技",
        "https://jobs.star.com",
        "hr@star.com"
    );

    deliveryService.deliver(config, "cand@example.com", "测试主题", "测试内容", "BEGIN:VCALENDAR");

    verify(weComMailClient).sendMail(config, "cand@example.com", "测试主题", "测试内容", "BEGIN:VCALENDAR");
    verify(repository, never()).findById(any());
  }
}
