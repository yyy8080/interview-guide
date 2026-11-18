package interview.guide.modules.recruitment.model;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.Locale;
import java.util.Set;

/**
 * 候选人应聘定位规范化与文案转换（junior 初级 / mid 中级 / senior 高级）
 */
public final class CandidateLevels {

  public static final String JUNIOR = "junior";
  public static final String MID = "mid";
  public static final String SENIOR = "senior";

  private static final Set<String> ALLOWED = Set.of(JUNIOR, MID, SENIOR);

  private CandidateLevels() {
  }

  /**
   * 校验并规范化输入；为空或空白返回 null，非法值抛出 BAD_REQUEST
   */
  public static String normalizeOrNull(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim().toLowerCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return null;
    }
    if (!ALLOWED.contains(normalized)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "应聘定位只能选择初级（junior）、中级（mid）或高级（senior）");
    }
    return normalized;
  }

  /**
   * 解析最终生效的出题定位：优先使用候选人自选定位，其次使用岗位默认难度，兜底中级
   */
  public static String resolve(String candidateLevel, String jobDifficulty) {
    String candidate = safeNormalize(candidateLevel);
    if (candidate != null) {
      return candidate;
    }
    String fallback = safeNormalize(jobDifficulty);
    return fallback != null ? fallback : MID;
  }

  public static String label(String level) {
    if (level == null) {
      return "中级";
    }
    return switch (level.trim().toLowerCase(Locale.ROOT)) {
      case JUNIOR -> "初级";
      case SENIOR -> "高级";
      default -> "中级";
    };
  }

  /**
   * 面向 AI 出题与面试官提示词的具体定位考察要求
   */
  public static String questionGuidance(String level) {
    String resolved = resolve(level, null);
    return switch (resolved) {
      case JUNIOR -> "面向【初级】定位（0-1年经验）：重点考察核心基础概念、语言与框架基本特性、常用 API/命令及简单业务实现；追问点到为止，避免偏门底层源码细节与超大规模分布式架构题。";
      case SENIOR -> "面向【高级】定位（3年以上经验）：重点考察底层核心机制深度、高并发高可用系统架构设计、技术选型权衡、性能瓶颈调优与复杂线上故障排障；减少纯概念背诵题，追问直击边界条件与工程取舍。";
      default -> "面向【中级】定位（1-3年经验）：重点考察核心组件底层工作原理、并发/事务/索引/缓存机制、常见生产工程实践与典型问题排查，要求知其然更知其所以然。";
    };
  }

  private static String safeNormalize(String value) {
    if (value == null) {
      return null;
    }
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    return ALLOWED.contains(normalized) ? normalized : null;
  }
}
