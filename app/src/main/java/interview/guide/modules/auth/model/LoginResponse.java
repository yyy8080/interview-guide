package interview.guide.modules.auth.model;

import java.time.Instant;

/**
 * 登录响应：访问令牌 + 当前账号
 */
public record LoginResponse(
    String token,
    Instant expiresAt,
    AdminUserDTO user
) {}
