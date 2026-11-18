package interview.guide.common.config;

import java.time.Duration;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 招聘流程配置（app.recruitment.*）
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.recruitment")
public class RecruitmentProperties {

  /** 候选人门户访问地址（邮件链接），后台邮件配置未填写时使用 */
  private String portalBaseUrl = "http://localhost:5173";

  /** 公司名称默认值，后台邮件配置未填写时使用 */
  private String companyName = "招聘团队";

  /** 在线面试邀请有效期（天），超时未开始面试转为已过期 */
  private int inviteExpireDays = 7;

  /** 岗位未配置初筛阈值时的默认值（宽进严出，主要过滤方向明显不符或无效简历） */
  private int defaultScreeningThreshold = 45;

  /** 语音面试未配置时长时的默认时长（分钟） */
  private int defaultVoiceDurationMinutes = 30;

  /** AI 判定 REJECT 的置信度达到该值时即使分数过线也判为不通过 */
  private double rejectConfidenceThreshold = 0.8;

  private Otp otp = new Otp();

  private Task task = new Task();

  @Data
  public static class Otp {

    /** 验证码有效期 */
    private Duration ttl = Duration.ofMinutes(10);

    /** 同一邮箱重发冷却时间 */
    private Duration cooldown = Duration.ofSeconds(60);

    /** 同一邮箱每日最多发送次数 */
    private int dailyLimit = 10;

    /** 单个验证码最多校验次数 */
    private int maxAttempts = 5;

    /** 邮件服务未启用时是否把验证码打印到日志（仅开发环境开启） */
    private boolean logCodeWhenMailDisabled = false;
  }

  @Data
  public static class Task {

    /** 恢复调度开关 */
    private boolean recoveryEnabled = true;

    /** 初筛 / 决策任务超过该时长未推进视为卡住 */
    private Duration staleAfter = Duration.ofMinutes(10);

    /** 面试评估超过该时长仍未完成时转人工审核 */
    private Duration evaluationTimeout = Duration.ofHours(2);

    /** 自动恢复最多重新入队次数，超过后走兜底策略 */
    private int maxRecoveryAttempts = 3;

    /** 每轮恢复的最大条数 */
    private int batchSize = 50;
  }
}
