package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.JwtTokenService;
import interview.guide.common.security.SecurityProperties;
import interview.guide.modules.auth.model.AdminRole;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.auth.model.ChangePasswordRequest;
import interview.guide.modules.auth.model.LoginRequest;
import interview.guide.modules.auth.model.LoginResponse;
import interview.guide.modules.auth.repository.AdminUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("后台登录与改密测试")
class AuthServiceTest {

  @Mock
  private AdminUserRepository adminUserRepository;

  @SuppressWarnings("deprecation")
  private final PasswordEncoder passwordEncoder = NoOpPasswordEncoder.getInstance();

  private JwtTokenService jwtTokenService;
  private AuthService authService;

  @BeforeEach
  void setUp() {
    SecurityProperties properties = new SecurityProperties();
    properties.setJwtSecret("test-secret-0123456789-abcdefghijklmnop");
    jwtTokenService = new JwtTokenService(properties);
    authService = new AuthService(adminUserRepository, passwordEncoder, jwtTokenService);
  }

  private static AdminUserEntity user(boolean enabled) {
    AdminUserEntity user = new AdminUserEntity();
    user.setId(1L);
    user.setUsername("hr01");
    user.setPasswordHash("secret123");
    user.setRole(AdminRole.HR);
    user.setEnabled(enabled);
    return user;
  }

  @Test
  @DisplayName("账号密码正确时登录成功并记录登录时间")
  void loginSuccess() {
    AdminUserEntity user = user(true);
    when(adminUserRepository.findByUsername("hr01")).thenReturn(Optional.of(user));
    when(adminUserRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    LoginResponse response = authService.login(new LoginRequest(" hr01 ", "secret123"));

    assertThat(response.token()).isNotBlank();
    assertThat(response.user().username()).isEqualTo("hr01");
    assertThat(user.getLastLoginAt()).isNotNull();
  }

  @Test
  @DisplayName("密码错误返回统一的凭证错误")
  void loginWrongPassword() {
    when(adminUserRepository.findByUsername("hr01")).thenReturn(Optional.of(user(true)));

    assertThatThrownBy(() -> authService.login(new LoginRequest("hr01", "wrong")))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.getCode());
    verify(adminUserRepository, never()).save(any());
  }

  @Test
  @DisplayName("账号不存在时返回与密码错误相同的错误码")
  void loginUnknownUser() {
    when(adminUserRepository.findByUsername("ghost")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authService.login(new LoginRequest("ghost", "whatever")))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.AUTH_INVALID_CREDENTIALS.getCode());
  }

  @Test
  @DisplayName("禁用账号无法登录")
  void loginDisabled() {
    when(adminUserRepository.findByUsername("hr01")).thenReturn(Optional.of(user(false)));

    assertThatThrownBy(() -> authService.login(new LoginRequest("hr01", "secret123")))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.AUTH_ACCOUNT_DISABLED.getCode());
  }

  @Test
  @DisplayName("修改密码后令牌版本递增，并返回新令牌")
  void changePasswordRevokesTokens() {
    AdminUserEntity user = user(true);
    when(adminUserRepository.findById(1L)).thenReturn(Optional.of(user));
    when(adminUserRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    LoginResponse response = authService.changePassword(1L, new ChangePasswordRequest("secret123", "newSecret456"));

    assertThat(user.getTokenVersion()).isEqualTo(1);
    assertThat(user.getPasswordHash()).isEqualTo("newSecret456");
    Object version = jwtTokenService.decoder().decode(response.token()).getClaim(JwtTokenService.CLAIM_TOKEN_VERSION);
    assertThat(((Number) version).intValue()).isEqualTo(1);
  }

  @Test
  @DisplayName("原密码错误时不允许修改")
  void changePasswordWrongOld() {
    AdminUserEntity user = user(true);
    when(adminUserRepository.findById(1L)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> authService.changePassword(1L, new ChangePasswordRequest("bad", "newSecret456")))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.AUTH_PASSWORD_INCORRECT.getCode());
    assertThat(user.getTokenVersion()).isZero();
  }
}
