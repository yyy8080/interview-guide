package interview.guide.modules.mail.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 后台邮件发送配置（单行表，id 固定为 1，支持 SMTP 与企业微信邮件 API）
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "mail_settings")
public class MailSettingsEntity {

  public static final long SINGLETON_ID = 1L;

  @Id
  private Long id = SINGLETON_ID;

  @Column(nullable = false)
  private boolean enabled = false;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private MailChannel channel = MailChannel.SMTP;

  private String host;

  private Integer port;

  private String username;

  @Column(name = "password_ciphertext", columnDefinition = "TEXT")
  private String passwordCiphertext;

  @Column(name = "password_nonce", length = 64)
  private String passwordNonce;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private MailSecurity security = MailSecurity.SSL;

  @Column(name = "from_address")
  private String fromAddress;

  @Column(name = "from_name", length = 128)
  private String fromName;

  @Column(name = "wecom_corp_id", length = 128)
  private String wecomCorpId;

  @Column(name = "wecom_secret_ciphertext", columnDefinition = "TEXT")
  private String wecomSecretCiphertext;

  @Column(name = "wecom_secret_nonce", length = 64)
  private String wecomSecretNonce;

  @Column(name = "wecom_api_base_url", length = 512)
  private String wecomApiBaseUrl;

  /** 公司名称，用于邮件模板 {companyName} */
  @Column(name = "company_name", length = 128)
  private String companyName;

  /** 候选人门户访问地址，用于邮件中的链接 */
  @Column(name = "portal_base_url", length = 512)
  private String portalBaseUrl;

  /** 联系方式，用于邮件模板 {contactInfo} */
  @Column(name = "contact_info", length = 512)
  private String contactInfo;

  @Column(name = "updated_by")
  private Long updatedBy;

  @Column(name = "updated_at", nullable = false)
  private LocalDateTime updatedAt;

  @PrePersist
  @PreUpdate
  void touch() {
    updatedAt = LocalDateTime.now();
  }

  public boolean hasPassword() {
    return passwordCiphertext != null && !passwordCiphertext.isBlank();
  }

  public boolean hasWecomSecret() {
    return wecomSecretCiphertext != null && !wecomSecretCiphertext.isBlank();
  }
}
