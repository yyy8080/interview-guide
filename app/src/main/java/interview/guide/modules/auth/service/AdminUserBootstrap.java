package interview.guide.modules.auth.service;

import interview.guide.common.security.SecurityProperties;
import interview.guide.modules.auth.model.AdminRole;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.auth.repository.AdminUserRepository;
import java.security.SecureRandom;
import java.util.Base64;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 首次启动（无任何后台账号）时创建初始管理员。
 * 未配置 APP_SECURITY_INIT_ADMIN_PASSWORD 时生成随机密码并在日志中输出一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminUserBootstrap implements ApplicationRunner {

  private static final int GENERATED_PASSWORD_BYTES = 12;

  private final AdminUserRepository adminUserRepository;
  private final PasswordEncoder passwordEncoder;
  private final SecurityProperties securityProperties;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (adminUserRepository.count() > 0) {
      return;
    }
    SecurityProperties.InitAdmin init = securityProperties.getInitAdmin();
    String password = init.getPassword();
    boolean generated = password == null || password.isBlank();
    if (generated) {
      byte[] bytes = new byte[GENERATED_PASSWORD_BYTES];
      new SecureRandom().nextBytes(bytes);
      password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    AdminUserEntity admin = new AdminUserEntity();
    admin.setUsername(init.getUsername());
    admin.setPasswordHash(passwordEncoder.encode(password));
    admin.setDisplayName(init.getDisplayName());
    admin.setEmail(init.getEmail() == null || init.getEmail().isBlank() ? null : init.getEmail());
    admin.setRole(AdminRole.ADMIN);
    admin.setEnabled(true);
    adminUserRepository.save(admin);

    if (generated) {
      log.warn("""

          ============================================================
           已创建初始管理员账号：{}
           随机密码：{}
           请登录后台后立即修改密码（该密码仅在本次启动日志中出现）
          ============================================================""",
          init.getUsername(), password);
    } else {
      log.info("已按配置创建初始管理员账号: {}", init.getUsername());
    }
  }
}
