package interview.guide.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.modules.auth.model.AdminRole;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.common.security.JwtTokenService.IssuedToken;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

@DisplayName("后台 JWT 签发与校验测试")
class JwtTokenServiceTest {

  private static final String SECRET = "test-secret-0123456789-abcdefghijklmnop";

  private static SecurityProperties properties(String secret) {
    SecurityProperties properties = new SecurityProperties();
    properties.setJwtSecret(secret);
    properties.setTokenTtl(Duration.ofHours(1));
    return properties;
  }

  private static AdminUserEntity user() {
    AdminUserEntity user = new AdminUserEntity();
    user.setId(42L);
    user.setUsername("hr01");
    user.setRole(AdminRole.HR);
    user.setTokenVersion(3);
    return user;
  }

  @Test
  @DisplayName("签发的令牌可被解码，并携带账号、角色与令牌版本")
  void issueAndDecodeRoundTrip() {
    JwtTokenService service = new JwtTokenService(properties(SECRET));

    IssuedToken issued = service.issue(user());
    Jwt jwt = service.decoder().decode(issued.token());

    assertThat(jwt.getSubject()).isEqualTo("42");
    assertThat(jwt.getClaimAsString(JwtTokenService.CLAIM_USERNAME)).isEqualTo("hr01");
    assertThat(jwt.getClaimAsString(JwtTokenService.CLAIM_ROLE)).isEqualTo("HR");
    assertThat(((Number) jwt.getClaim(JwtTokenService.CLAIM_TOKEN_VERSION)).intValue()).isEqualTo(3);
    assertThat(issued.expiresAt()).isAfter(Instant.now().plus(Duration.ofMinutes(59)));
  }

  @Test
  @DisplayName("其他密钥签发的令牌校验失败")
  void rejectsTokenSignedWithOtherSecret() {
    JwtTokenService issuer = new JwtTokenService(properties("another-secret-0123456789-abcdefghijkl"));
    JwtTokenService verifier = new JwtTokenService(properties(SECRET));

    String token = issuer.issue(user()).token();

    assertThatThrownBy(() -> verifier.decoder().decode(token)).isInstanceOf(JwtException.class);
  }

  @Test
  @DisplayName("密钥不足 32 字节时启动失败")
  void rejectsShortSecret() {
    assertThatThrownBy(() -> new JwtTokenService(properties("too-short")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("未配置密钥时随机生成，仍可正常签发校验")
  void generatesRandomSecretWhenBlank() {
    JwtTokenService service = new JwtTokenService(properties(""));

    String token = service.issue(user()).token();

    assertThat(service.decoder().decode(token).getSubject()).isEqualTo("42");
  }

  @Test
  @DisplayName("候选人令牌可正常签发并解析出候选人 ID")
  void issueAndVerifyCandidateToken() {
    JwtTokenService service = new JwtTokenService(properties(SECRET));

    IssuedToken issued = service.issueCandidate(108L, "alice@example.com");

    assertThat(service.verifyCandidate(issued.token())).isEqualTo(108L);
    assertThat(issued.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(23)));
  }

  @Test
  @DisplayName("候选人令牌与后台令牌严格隔离，互相拒绝混用")
  void isolatesAdminAndCandidateTokens() {
    JwtTokenService service = new JwtTokenService(properties(SECRET));

    String adminToken = service.issue(user()).token();
    String candidateToken = service.issueCandidate(108L, "alice@example.com").token();

    assertThat(service.verifyCandidate(adminToken)).isNull();
    assertThat(service.verifyCandidate("")).isNull();
    assertThat(service.verifyCandidate("invalid.jwt.token")).isNull();
    assertThatThrownBy(() -> service.decoder().decode(candidateToken))
        .isInstanceOf(JwtException.class);
  }
}
