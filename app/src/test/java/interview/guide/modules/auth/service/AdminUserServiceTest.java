package interview.guide.modules.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.model.AdminRole;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.auth.model.CreateAdminUserRequest;
import interview.guide.modules.auth.model.UpdateAdminUserRequest;
import interview.guide.modules.auth.repository.AdminUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;

@ExtendWith(MockitoExtension.class)
@DisplayName("后台账号管理测试")
class AdminUserServiceTest {

  @Mock
  private AdminUserRepository adminUserRepository;

  private AdminUserService service;

  @BeforeEach
  @SuppressWarnings("deprecation")
  void setUp() {
    service = new AdminUserService(adminUserRepository, NoOpPasswordEncoder.getInstance());
  }

  private static AdminUserEntity admin(Long id) {
    AdminUserEntity user = new AdminUserEntity();
    user.setId(id);
    user.setUsername("admin" + id);
    user.setPasswordHash("x");
    user.setRole(AdminRole.ADMIN);
    user.setEnabled(true);
    return user;
  }

  @Test
  @DisplayName("用户名重复时创建失败")
  void createDuplicateUsername() {
    when(adminUserRepository.existsByUsername("hr01")).thenReturn(true);

    assertThatThrownBy(() -> service.create(new CreateAdminUserRequest("hr01", "password1", null, null, AdminRole.HR)))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.ADMIN_USER_ALREADY_EXISTS.getCode());
  }

  @Test
  @DisplayName("不能取消自己的管理员权限")
  void cannotDemoteSelf() {
    when(adminUserRepository.findById(1L)).thenReturn(Optional.of(admin(1L)));

    assertThatThrownBy(() -> service.update(1L, 1L, new UpdateAdminUserRequest(null, null, AdminRole.HR, true)))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.ADMIN_LAST_ADMIN_PROTECTED.getCode());
  }

  @Test
  @DisplayName("禁用最后一个管理员时被拒绝")
  void cannotDisableLastAdmin() {
    when(adminUserRepository.findById(2L)).thenReturn(Optional.of(admin(2L)));
    when(adminUserRepository.countByRoleAndEnabledTrue(AdminRole.ADMIN)).thenReturn(1L);

    assertThatThrownBy(() -> service.update(1L, 2L, new UpdateAdminUserRequest(null, null, AdminRole.ADMIN, false)))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.ADMIN_LAST_ADMIN_PROTECTED.getCode());
  }

  @Test
  @DisplayName("仍有其他管理员时可禁用，且吊销其令牌")
  void disableAdminRevokesTokens() {
    AdminUserEntity target = admin(2L);
    when(adminUserRepository.findById(2L)).thenReturn(Optional.of(target));
    when(adminUserRepository.countByRoleAndEnabledTrue(AdminRole.ADMIN)).thenReturn(2L);
    when(adminUserRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.update(1L, 2L, new UpdateAdminUserRequest("张三", null, AdminRole.ADMIN, false));

    assertThat(target.isEnabled()).isFalse();
    assertThat(target.getTokenVersion()).isEqualTo(1);
    assertThat(target.getDisplayName()).isEqualTo("张三");
  }
}
