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
 * 后台 SMTP 配置（单行表，id 固定为 1）
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
}
