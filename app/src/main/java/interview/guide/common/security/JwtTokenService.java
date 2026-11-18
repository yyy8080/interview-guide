package interview.guide.common.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import interview.guide.modules.auth.model.AdminUserEntity;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * 后台 JWT 签发与校验（HS256）
 */
@Slf4j
@Component
public class JwtTokenService {

  public static final String CLAIM_USERNAME = "username";
  public static final String CLAIM_ROLE = "role";
  public static final String CLAIM_TOKEN_VERSION = "ver";
  public static final String CLAIM_EMAIL = "email";

  /** 候选人令牌使用独立的签发方，后台解码器会因签发方不符拒绝候选人令牌，反之亦然 */
  private static final String CANDIDATE_ISSUER_SUFFIX = ":candidate";

  private static final int MIN_SECRET_BYTES = 32;
  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private final SecurityProperties properties;
  private final JwtEncoder encoder;
  private final JwtDecoder decoder;
  private final JwtDecoder candidateDecoder;

  public JwtTokenService(SecurityProperties properties) {
    this.properties = properties;
    SecretKey key = buildKey(properties.getJwtSecret());
    this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
    NimbusJwtDecoder nimbusDecoder = NimbusJwtDecoder.withSecretKey(key)
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
    nimbusDecoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.getJwtIssuer()));
    this.decoder = nimbusDecoder;
    NimbusJwtDecoder candidate = NimbusJwtDecoder.withSecretKey(key)
        .macAlgorithm(MacAlgorithm.HS256)
        .build();
    candidate.setJwtValidator(JwtValidators.createDefaultWithIssuer(candidateIssuer()));
    this.candidateDecoder = candidate;
  }

  /**
   * 为后台账号签发访问令牌
   */
  public IssuedToken issue(AdminUserEntity user) {
    Instant now = Instant.now();
    Instant expiresAt = now.plus(properties.getTokenTtl());
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(properties.getJwtIssuer())
        .subject(String.valueOf(user.getId()))
        .issuedAt(now)
        .expiresAt(expiresAt)
        .claim(CLAIM_USERNAME, user.getUsername())
        .claim(CLAIM_ROLE, user.getRole().name())
        .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
        .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedToken(token, expiresAt);
  }

  public JwtDecoder decoder() {
    return decoder;
  }

  /**
   * 为通过邮箱验证码校验的候选人签发访问令牌
   */
  public IssuedToken issueCandidate(Long candidateId, String email) {
    Instant now = Instant.now();
    Instant expiresAt = now.plus(properties.getCandidateTokenTtl());
    JwtClaimsSet claims = JwtClaimsSet.builder()
        .issuer(candidateIssuer())
        .subject(String.valueOf(candidateId))
        .issuedAt(now)
        .expiresAt(expiresAt)
        .claim(CLAIM_EMAIL, email)
        .build();
    JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
    String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    return new IssuedToken(token, expiresAt);
  }

  /**
   * 校验候选人令牌，返回候选人 ID；无效或过期时返回 null
   */
  public Long verifyCandidate(String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      Jwt jwt = candidateDecoder.decode(token);
      return Long.valueOf(jwt.getSubject());
    } catch (JwtException | NumberFormatException e) {
      return null;
    }
  }

  private String candidateIssuer() {
    return properties.getJwtIssuer() + CANDIDATE_ISSUER_SUFFIX;
  }

  private static SecretKey buildKey(String secret) {
    byte[] bytes;
    if (secret == null || secret.isBlank()) {
      bytes = new byte[MIN_SECRET_BYTES];
      new SecureRandom().nextBytes(bytes);
      log.warn("未配置 APP_SECURITY_JWT_SECRET，已随机生成 JWT 密钥；服务重启后所有登录状态将失效，生产环境请务必配置");
    } else {
      bytes = secret.getBytes(StandardCharsets.UTF_8);
      if (bytes.length < MIN_SECRET_BYTES) {
        throw new IllegalStateException("APP_SECURITY_JWT_SECRET 长度不足 " + MIN_SECRET_BYTES + " 字节");
      }
    }
    return new SecretKeySpec(bytes, HMAC_ALGORITHM);
  }

  public record IssuedToken(String token, Instant expiresAt) {}
}
