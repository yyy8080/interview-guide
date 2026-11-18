package interview.guide.modules.recruitment.model;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import java.util.Locale;
import java.util.Set;

/**
 * 候选人答题前选填的 AI 相关经验枚举规范化与个人特长清洗工具
 */
public final class CandidateAiExperiences {

  /** 暂无 AI 相关经验 */
  public static final String NONE = "NONE";
  /** 日常使用 AI 工具辅助研发 / 办公提效 */
  public static final String TOOL_USER = "TOOL_USER";
  /** 有 AI / 大模型应用、RAG、Agent 或模型微调等项目实战经验 */
  public static final String PROJECT_PRACTICE = "PROJECT_PRACTICE";

  public static final int MAX_SPECIALTIES_LENGTH = 1000;

  private static final Set<String> ALLOWED = Set.of(NONE, TOOL_USER, PROJECT_PRACTICE);

  private CandidateAiExperiences() {
  }

  /**
   * 规范化 AI 经验档位；为空或空白返回 null，非法值抛出 BAD_REQUEST
   */
  public static String normalizeOrNull(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    if (normalized.isEmpty()) {
      return null;
    }
    if (!ALLOWED.contains(normalized)) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "AI 相关经验只能选择 NONE、TOOL_USER 或 PROJECT_PRACTICE");
    }
    return normalized;
  }

  /**
   * 清洗候选人填写的其他特长文本；空白返回 null，超出长度抛出 BAD_REQUEST
   */
  public static String sanitizeSpecialties(String raw) {
    if (raw == null) {
      return null;
    }
    String cleaned = raw.replace("\u0000", "").replace("\r\n", "\n").trim();
    if (cleaned.isEmpty()) {
      return null;
    }
    if (cleaned.length() > MAX_SPECIALTIES_LENGTH) {
      throw new BusinessException(ErrorCode.BAD_REQUEST,
          "其他特长与补充说明不能超过 " + MAX_SPECIALTIES_LENGTH + " 字");
    }
    return cleaned;
  }

  public static String label(String aiExperience) {
    if (aiExperience == null || aiExperience.isBlank()) {
      return null;
    }
    return switch (aiExperience.trim().toUpperCase(Locale.ROOT)) {
      case NONE -> "暂无 AI 相关经验";
      case TOOL_USER -> "日常使用 AI 工具辅助提效";
      case PROJECT_PRACTICE -> "有 AI / 大模型项目实战经验";
      default -> null;
    };
  }

  /**
   * 生成注入 AI 出题 / 面试追问 / 综合决策提示词的候选人补充背景描述
   */
  public static String describeForPrompt(String aiExperience, String specialties) {
    String expLabel = label(aiExperience);
    String spec = specialties == null || specialties.isBlank() ? null : specialties.trim();
    if (expLabel == null && spec == null) {
      return "（候选人未额外填写 AI 相关经验或其他特长）";
    }
    StringBuilder sb = new StringBuilder();
    if (expLabel != null) {
      sb.append("- AI 相关经验：").append(expLabel).append('\n');
    }
    if (spec != null) {
      sb.append("- 其他个人特长与补充说明：").append(spec).append('\n');
    }
    return sb.toString().trim();
  }
}
