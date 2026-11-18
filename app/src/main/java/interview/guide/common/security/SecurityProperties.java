package interview.guide.common.security;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 后台认证配置（app.security.*）
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

  /**
   * JWT HS256 签名密钥（至少 32 字节）。为空时启动随机生成，重启后已签发令牌全部失效。
   */
  private String jwtSecret = "";

  /** 令牌签发方 */
  private String jwtIssuer = "interview-guide";

  /** 访问令牌有效期 */
  private Duration tokenTtl = Duration.ofHours(8);

  /** 候选人（邮箱验证码登录）令牌有效期 */
  private Duration candidateTokenTtl = Duration.ofDays(7);

  /** 是否允许匿名访问 Swagger / OpenAPI 文档（生产环境建议关闭） */
  private boolean publicApiDocs = true;

  /** 首次启动时自动创建的管理员账号 */
  private InitAdmin initAdmin = new InitAdmin();

  @Data
  public static class InitAdmin {

    private String username = "admin";

    /** 为空时随机生成并在日志中打印一次 */
    private String password = "";

    private String displayName = "系统管理员";

    private String email = "";
  }
}
