package interview.guide.common.security;

import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.auth.repository.AdminUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * 将 JWT 转换为认证对象。
 * <p>
 * 每次请求回查账号：账号禁用、令牌版本变化（改密/吊销）后旧令牌立即失效；
 * 角色以数据库为准，调整角色无需重新登录。
 */
@Component
@RequiredArgsConstructor
public class AdminJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

  private final AdminUserRepository adminUserRepository;

  @Override
  public AbstractAuthenticationToken convert(Jwt jwt) {
    Long userId = parseUserId(jwt.getSubject());
    AdminUserEntity user = adminUserRepository.findById(userId)
        .orElseThrow(() -> new InvalidBearerTokenException("账号不存在"));
    if (!user.isEnabled()) {
      throw new InvalidBearerTokenException("账号已被禁用");
    }
    Object version = jwt.getClaim(JwtTokenService.CLAIM_TOKEN_VERSION);
    if (!(version instanceof Number number) || number.intValue() != user.getTokenVersion()) {
      throw new InvalidBearerTokenException("登录状态已失效，请重新登录");
    }
    List<SimpleGrantedAuthority> authorities =
        List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
    return new JwtAuthenticationToken(jwt, authorities, user.getUsername());
  }

  private Long parseUserId(String subject) {
    try {
      return Long.valueOf(subject);
    } catch (NumberFormatException e) {
      throw new InvalidBearerTokenException("令牌格式无效");
    }
  }
}
