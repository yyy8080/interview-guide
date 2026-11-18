package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.AdminRole;
import interview.guide.modules.auth.model.AdminUserDTO;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.auth.model.CreateAdminUserRequest;
import interview.guide.modules.auth.model.ResetPasswordRequest;
import interview.guide.modules.auth.model.UpdateAdminUserRequest;
import interview.guide.modules.auth.repository.AdminUserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台账号管理（仅 ADMIN）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserService {

  private final AdminUserRepository adminUserRepository;
  private final PasswordEncoder passwordEncoder;

  @Transactional(readOnly = true)
  public List<AdminUserDTO> list() {
    return adminUserRepository.findAllByOrderByCreatedAtAsc().stream()
        .map(AdminUserDTO::from)
        .toList();
  }

  @Transactional
  public AdminUserDTO create(CreateAdminUserRequest request) {
    String username = request.username().trim();
    if (adminUserRepository.existsByUsername(username)) {
      throw new BusinessException(ErrorCode.ADMIN_USER_ALREADY_EXISTS);
    }
    AdminUserEntity user = new AdminUserEntity();
    user.setUsername(username);
    user.setPasswordHash(passwordEncoder.encode(request.password()));
    user.setDisplayName(blankToNull(request.displayName()));
    user.setEmail(blankToNull(request.email()));
    user.setRole(request.role());
    user.setEnabled(true);
    AdminUserEntity saved = adminUserRepository.save(user);
    log.info("创建后台账号: userId={}, role={}", saved.getId(), saved.getRole());
    return AdminUserDTO.from(saved);
  }

  @Transactional
  public AdminUserDTO update(Long operatorId, Long userId, UpdateAdminUserRequest request) {
    AdminUserEntity user = getOrThrow(userId);
    boolean losesAdmin = user.getRole() == AdminRole.ADMIN && user.isEnabled()
        && (request.role() != AdminRole.ADMIN || !request.enabled());
    if (losesAdmin) {
      if (userId.equals(operatorId)) {
        throw new BusinessException(ErrorCode.ADMIN_LAST_ADMIN_PROTECTED, "不能取消自己的管理员权限或禁用自己");
      }
      ensureNotLastAdmin();
    }
    boolean revoke = user.isEnabled() && !request.enabled();
    user.setDisplayName(blankToNull(request.displayName()));
    user.setEmail(blankToNull(request.email()));
    user.setRole(request.role());
    user.setEnabled(request.enabled());
    if (revoke) {
      user.revokeTokens();
    }
    log.info("更新后台账号: userId={}, role={}, enabled={}", userId, request.role(), request.enabled());
    return AdminUserDTO.from(adminUserRepository.save(user));
  }

  @Transactional
  public void resetPassword(Long userId, ResetPasswordRequest request) {
    AdminUserEntity user = getOrThrow(userId);
    user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    user.revokeTokens();
    adminUserRepository.save(user);
    log.info("重置后台账号密码: userId={}", userId);
  }

  private void ensureNotLastAdmin() {
    if (adminUserRepository.countByRoleAndEnabledTrue(AdminRole.ADMIN) <= 1) {
      throw new BusinessException(ErrorCode.ADMIN_LAST_ADMIN_PROTECTED);
    }
  }

  private AdminUserEntity getOrThrow(Long userId) {
    return adminUserRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_NOT_FOUND));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
