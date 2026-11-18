package interview.guide.modules.recruitment.service;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.security.JwtTokenService;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.mail.service.MailDeliveryService;
import interview.guide.modules.mail.service.MailOutboxService;
import interview.guide.modules.mail.service.MailSettingsService;
import interview.guide.modules.recruitment.model.CandidateEntity;
import interview.guide.modules.recruitment.model.CandidateLoginResponse;
import interview.guide.modules.recruitment.model.CandidateProfileDTO;
import interview.guide.modules.recruitment.repository.CandidateRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 候选人邮箱验证码登录：免注册，邮箱即身份凭证。
 * <p>
 * 验证码只保存 SHA-256 摘要；同一邮箱有发送冷却、每日上限与校验次数上限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateAuthService {

  public static final String TOKEN_HEADER = "X-Candidate-Token";

  static final String CODE_KEY = "candidate:otp:code:";
  static final String ATTEMPT_KEY = "candidate:otp:attempts:";
  static final String COOLDOWN_KEY = "candidate:otp:cooldown:";
  static final String DAILY_KEY = "candidate:otp:daily:";

  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");
  private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

  private final RedisService redisService;
  private final MailOutboxService mailOutboxService;
  private final MailSettingsService mailSettingsService;
  private final CandidateRepository candidateRepository;
  private final JwtTokenService jwtTokenService;
  private final TransactionalExecutor transactionalExecutor;
  private final RecruitmentProperties properties;
  private final RecruitmentInvitationService invitationService;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * 发送登录验证码
   */
  public void sendCode(String rawEmail) {
    String email = normalizeEmail(rawEmail);
    invitationService.assertCanAuthenticate(email);
    RecruitmentProperties.Otp otp = properties.getOtp();

    if (redisService.exists(COOLDOWN_KEY + email)) {
      throw new BusinessException(ErrorCode.VERIFICATION_CODE_TOO_FREQUENT);
    }
    String dailyKey = DAILY_KEY + LocalDate.now().format(DAY) + ":" + email;
    long sentToday = redisService.increment(dailyKey);
    if (sentToday == 1) {
      redisService.expire(dailyKey, Duration.ofDays(1));
    }
    if (sentToday > otp.getDailyLimit()) {
      throw new BusinessException(ErrorCode.VERIFICATION_CODE_TOO_FREQUENT, "今日验证码发送次数已达上限，请明天再试");
    }

    String code = String.format("%06d", secureRandom.nextInt(1_000_000));
    redisService.set(CODE_KEY + email, hash(email, code), otp.getTtl());
    redisService.delete(ATTEMPT_KEY + email);
    redisService.set(COOLDOWN_KEY + email, "1", otp.getCooldown());

    boolean mailEnabled = mailSettingsService.resolve().enabled();
    if (!mailEnabled && otp.isLogCodeWhenMailDisabled()) {
      log.warn("【仅限开发环境】邮件服务未启用，候选人验证码: email={}, code={}", email, code);
      return;
    }
    try {
      mailOutboxService.sendVerificationCode(email, code, otp.getTtl().toMinutes());
      log.info("候选人验证码已发送: email={}", MailDeliveryService.maskEmail(email));
    } catch (BusinessException e) {
      // 发送失败时撤销验证码与冷却，允许立即重试
      redisService.delete(CODE_KEY + email);
      redisService.delete(COOLDOWN_KEY + email);
      if (ErrorCode.MAIL_NOT_CONFIGURED.getCode().equals(e.getCode())) {
        throw new BusinessException(ErrorCode.MAIL_NOT_CONFIGURED, "系统邮件服务暂未开启，请联系招聘方");
      }
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED, "验证码邮件发送失败，请稍后重试");
    }
  }

  /**
   * 校验验证码，成功后签发候选人令牌（首次登录自动创建候选人）
   */
  public CandidateLoginResponse verify(String rawEmail, String code) {
    String email = normalizeEmail(rawEmail);
    invitationService.assertCanAuthenticate(email);
    String stored = redisService.get(CODE_KEY + email);
    if (stored == null) {
      throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID);
    }
    long attempts = redisService.increment(ATTEMPT_KEY + email);
    if (attempts == 1) {
      redisService.expire(ATTEMPT_KEY + email, properties.getOtp().getTtl());
    }
    if (attempts > properties.getOtp().getMaxAttempts()) {
      redisService.delete(CODE_KEY + email);
      throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID, "验证码错误次数过多，请重新获取");
    }
    boolean matched = MessageDigest.isEqual(
        stored.getBytes(StandardCharsets.UTF_8), hash(email, code).getBytes(StandardCharsets.UTF_8));
    if (!matched) {
      throw new BusinessException(ErrorCode.VERIFICATION_CODE_INVALID);
    }
    redisService.delete(CODE_KEY + email);
    redisService.delete(ATTEMPT_KEY + email);

    CandidateEntity candidate = upsertCandidate(email);
    JwtTokenService.IssuedToken token = jwtTokenService.issueCandidate(candidate.getId(), candidate.getEmail());
    log.info("候选人登录成功: candidateId={}", candidate.getId());
    return new CandidateLoginResponse(token.token(), token.expiresAt(), toProfile(candidate));
  }

  /**
   * 解析请求头中的候选人令牌，无效时抛出 CANDIDATE_UNAUTHORIZED
   */
  public Long requireCandidateId(String token) {
    Long candidateId = jwtTokenService.verifyCandidate(token);
    if (candidateId == null) {
      throw new BusinessException(ErrorCode.CANDIDATE_UNAUTHORIZED);
    }
    return candidateId;
  }

  public CandidateProfileDTO me(Long candidateId) {
    CandidateEntity candidate = candidateRepository.findById(candidateId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CANDIDATE_UNAUTHORIZED));
    return toProfile(candidate);
  }

  public static String normalizeEmail(String email) {
    String normalized = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    if (normalized.length() > 255 || !EMAIL_PATTERN.matcher(normalized).matches()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确");
    }
    return normalized;
  }

  private CandidateEntity upsertCandidate(String email) {
    try {
      return transactionalExecutor.call(() -> {
        CandidateEntity candidate = candidateRepository.findByEmail(email).orElseGet(() -> {
          CandidateEntity created = new CandidateEntity();
          created.setEmail(email);
          return created;
        });
        candidate.setLastLoginAt(LocalDateTime.now());
        return candidateRepository.save(candidate);
      });
    } catch (DataIntegrityViolationException e) {
      // 并发首次登录：唯一约束冲突后读取已创建的记录
      return candidateRepository.findByEmail(email)
          .orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_ERROR, "候选人创建失败"));
    }
  }

  private static CandidateProfileDTO toProfile(CandidateEntity candidate) {
    return new CandidateProfileDTO(candidate.getId(), candidate.getEmail(), candidate.getName(),
        candidate.getPhone());
  }

  static String hash(String email, String code) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest((email + ":" + code).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(bytes);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
