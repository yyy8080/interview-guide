package interview.guide.common.security;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * 获取当前登录的后台账号
 */
public final class CurrentAdmin {

  private CurrentAdmin() {
  }

  /**
   * 当前账号 ID；未登录时抛出 UNAUTHORIZED
   */
  public static Long id() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwtToken) {
      try {
        return Long.valueOf(jwtToken.getToken().getSubject());
      } catch (NumberFormatException e) {
        throw new BusinessException(ErrorCode.UNAUTHORIZED, "登录状态无效");
      }
    }
    throw new BusinessException(ErrorCode.UNAUTHORIZED, "请先登录");
  }

  /**
   * 当前账号 ID；未登录时返回 null（用于审计字段等可选场景）
   */
  public static Long idOrNull() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication instanceof JwtAuthenticationToken jwtToken) {
      try {
        return Long.valueOf(jwtToken.getToken().getSubject());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
