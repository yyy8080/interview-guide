package interview.guide.modules.auth.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.JwtTokenService;
import interview.guide.common.security.JwtTokenService.IssuedToken;
import interview.guide.modules.auth.model.AdminUserDTO;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.auth.model.ChangePasswordRequest;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.LoginResponse;
import interview.guide.modules.auth.repository.AdminUserRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 后台登录与本人账号操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

  private final AdminUserRepository adminUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final JwtTokenService jwtTokenService;

  /** 用户不存在时也做一次哈希比对，避免通过响应耗时枚举用户名 */
  private volatile String dummyPasswordHash;

  @Transactional
  public LoginResponse login(LoginRequest request) {
    String username = request.username().trim();
    AdminUserEntity user = adminUserRepository.findByUsername(username).orElse(null);
    if (user == null) {
      passwordEncoder.matches(request.password(), dummyPasswordHash());
      log.info("后台登录失败: 账号不存在");
      throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
    if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
      log.info("后台登录失败: 密码错误, userId={}", user.getId());
      throw new BusinessException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }
    if (!user.isEnabled()) {
      throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
    }
    user.setLastLoginAt(LocalDateTime.now());
    adminUserRepository.save(user);
    IssuedToken token = jwtTokenService.issue(user);
    log.info("后台登录成功: userId={}, role={}", user.getId(), user.getRole());
    return new LoginResponse(token.token(), token.expiresAt(), AdminUserDTO.from(user));
  }

  @Transactional(readOnly = true)
  public AdminUserDTO me(Long userId) {
    return AdminUserDTO.from(getOrThrow(userId));
  }

  /**
   * 修改本人密码：成功后吊销旧令牌，并返回新令牌，当前会话无需重新登录
   */
  @Transactional
  public LoginResponse changePassword(Long userId, ChangePasswordRequest request) {
    AdminUserEntity user = getOrThrow(userId);
    if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
      throw new BusinessException(ErrorCode.AUTH_PASSWORD_INCORRECT);
    }
    user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
    user.revokeTokens();
    adminUserRepository.save(user);
    IssuedToken token = jwtTokenService.issue(user);
    log.info("后台账号修改密码: userId={}", userId);
    return new LoginResponse(token.token(), token.expiresAt(), AdminUserDTO.from(user));
  }

  private String dummyPasswordHash() {
    String hash = dummyPasswordHash;
    if (hash == null) {
      hash = passwordEncoder.encode("dummy-password-for-timing");
      dummyPasswordHash = hash;
    }
    return hash;
  }

  private AdminUserEntity getOrThrow(Long userId) {
    return adminUserRepository.findById(userId)
        .orElseThrow(() -> new BusinessException(ErrorCode.ADMIN_USER_NOT_FOUND));
  }
}
