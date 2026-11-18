package interview.guide.modules.auth.model;

import java.time.LocalDateTime;

/**
 * 后台账号信息（不含密码）
 */
public record AdminUserDTO(
    Long id,
    String username,
    String displayName,
    String email,
    AdminRole role,
    boolean enabled,
    LocalDateTime lastLoginAt,
    LocalDateTime createdAt
) {

  public static AdminUserDTO from(AdminUserEntity entity) {
    return new AdminUserDTO(
        entity.getId(),
        entity.getUsername(),
        entity.getDisplayName(),
        entity.getEmail(),
        entity.getRole(),
        entity.isEnabled(),
        entity.getLastLoginAt(),
        entity.getCreatedAt()
    );
  }
}
