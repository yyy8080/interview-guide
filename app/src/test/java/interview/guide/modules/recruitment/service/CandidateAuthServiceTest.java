package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.JwtTokenService;
import interview.guide.common.security.SecurityProperties;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.auth.model.AdminRole;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.mail.model.MailSecurity;
import interview.guide.modules.mail.service.MailOutboxService;
import interview.guide.modules.mail.service.MailSettingsService;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import interview.guide.modules.recruitment.model.CandidateEntity;
import interview.guide.modules.recruitment.model.CandidateLoginResponse;
import interview.guide.modules.recruitment.repository.CandidateRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("候选人邮箱验证码登录服务测试")
class CandidateAuthServiceTest {

  @Mock
  private RedisService redisService;

  @Mock
  private MailOutboxService mailOutboxService;

  @Mock
  private MailSettingsService mailSettingsService;

  @Mock
  private CandidateRepository candidateRepository;

  @Mock
  private TransactionalExecutor transactionalExecutor;

  private JwtTokenService jwtTokenService;
  private RecruitmentProperties properties;
  private CandidateAuthService authService;

  @BeforeEach
  void setUp() {
    SecurityProperties securityProperties = new SecurityProperties();
    securityProperties.setJwtSecret("candidate-auth-test-secret-0123456789");
    jwtTokenService = new JwtTokenService(securityProperties);
    properties = new RecruitmentProperties();
    authService = new CandidateAuthService(
        redisService, mailOutboxService, mailSettingsService,
        candidateRepository, jwtTokenService, transactionalExecutor, properties);
  }

  private ResolvedMailConfig enabledMail() {
    return new ResolvedMailConfig(true, "smtp.example.com", 465, "hr@example.com", "pwd",
        MailSecurity.SSL, "hr@example.com", "招聘团队", "测试科技", "http://localhost:5173", null);
  }

  @Test
  @DisplayName("发送验证码会归一化邮箱、保存哈希、设置冷却并调用邮件服务")
  void sendsOtpCodeAndSetsCooldown() {
    when(redisService.exists("candidate:otp:cooldown:alice@example.com")).thenReturn(false);
    when(redisService.increment(any())).thenReturn(1L);
    when(mailSettingsService.resolve()).thenReturn(enabledMail());

    authService.sendCode("  Alice@Example.com ");

    verify(redisService).set(eq("candidate:otp:code:alice@example.com"), any(String.class), any(Duration.class));
    verify(redisService).set(eq("candidate:otp:cooldown:alice@example.com"), eq("1"), any(Duration.class));
    verify(mailOutboxService).sendVerificationCode(eq("alice@example.com"), any(String.class), anyLong());
  }

  @Test
  @DisplayName("冷却期内重复发送或超出每日上限抛出频率限制异常")
  void blocksCooldownAndDailyLimit() {
    when(redisService.exists("candidate:otp:cooldown:alice@example.com")).thenReturn(true);

    assertThatThrownBy(() -> authService.sendCode("alice@example.com"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.VERIFICATION_CODE_TOO_FREQUENT.getCode());

    when(redisService.exists("candidate:otp:cooldown:bob@example.com")).thenReturn(false);
    when(redisService.increment(any())).thenReturn((long) properties.getOtp().getDailyLimit() + 1);

    assertThatThrownBy(() -> authService.sendCode("bob@example.com"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.VERIFICATION_CODE_TOO_FREQUENT.getCode());
    verify(mailOutboxService, never()).sendVerificationCode(any(), any(), anyLong());
  }

  @Test
  @DisplayName("邮件发送失败时清理已写入的验证码和冷却键，允许重试")
  void cleansUpRedisKeysWhenMailFails() {
    when(redisService.exists("candidate:otp:cooldown:alice@example.com")).thenReturn(false);
    when(redisService.increment(any())).thenReturn(1L);
    when(mailSettingsService.resolve()).thenReturn(enabledMail());
    doThrow(new BusinessException(ErrorCode.MAIL_NOT_CONFIGURED))
        .when(mailOutboxService).sendVerificationCode(eq("alice@example.com"), any(), anyLong());

    assertThatThrownBy(() -> authService.sendCode("alice@example.com"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.MAIL_NOT_CONFIGURED.getCode());

    verify(redisService).delete("candidate:otp:code:alice@example.com");
    verify(redisService).delete("candidate:otp:cooldown:alice@example.com");
  }

  @Test
  @DisplayName("验证码校验成功时自动创建候选人档案并签发候选人专属令牌")
  void verifiesCodeAndIssuesCandidateToken() {
    String email = "alice@example.com";
    String code = "654321";
    when(redisService.get("candidate:otp:code:" + email)).thenReturn(CandidateAuthService.hash(email, code));
    when(redisService.increment("candidate:otp:attempts:" + email)).thenReturn(1L);
    when(transactionalExecutor.call(any())).thenAnswer(invocation -> {
      Supplier<?> supplier = invocation.getArgument(0);
      return supplier.get();
    });
    when(candidateRepository.findByEmail(email)).thenReturn(Optional.empty());
    when(candidateRepository.save(any(CandidateEntity.class))).thenAnswer(invocation -> {
      CandidateEntity saved = invocation.getArgument(0);
      saved.setId(55L);
      return saved;
    });

    CandidateLoginResponse response = authService.verify("ALICE@example.com", code);

    assertThat(response.candidate().id()).isEqualTo(55L);
    assertThat(response.candidate().email()).isEqualTo(email);
    assertThat(authService.requireCandidateId(response.token())).isEqualTo(55L);
    verify(redisService).delete("candidate:otp:code:" + email);
    verify(redisService).delete("candidate:otp:attempts:" + email);
  }

  @Test
  @DisplayName("连续输错超过上限时立即作废验证码，且拒绝后台管理员令牌充当候选人凭证")
  void locksOutAfterMaxAttemptsAndRejectsAdminToken() {
    String email = "alice@example.com";
    when(redisService.get("candidate:otp:code:" + email)).thenReturn(CandidateAuthService.hash(email, "123456"));
    when(redisService.increment("candidate:otp:attempts:" + email))
        .thenReturn((long) properties.getOtp().getMaxAttempts() + 1);

    assertThatThrownBy(() -> authService.verify(email, "000000"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.VERIFICATION_CODE_INVALID.getCode());
    verify(redisService).delete("candidate:otp:code:" + email);

    AdminUserEntity admin = new AdminUserEntity();
    admin.setId(1L);
    admin.setUsername("admin");
    admin.setRole(AdminRole.ADMIN);
    admin.setTokenVersion(0);
    String adminToken = jwtTokenService.issue(admin).token();

    assertThatThrownBy(() -> authService.requireCandidateId(adminToken))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.CANDIDATE_UNAUTHORIZED.getCode());
  }
}
