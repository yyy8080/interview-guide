package interview.guide.modules.recruitment.model;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 候选人投递简历时选择的技术方向偏好（FRONTEND 偏向前端 / BACKEND 偏向后端）：
 * 统一用于后续 AI 简历初筛、在线笔试命题、文字/语音面试出题及 AI 综合评估。
 */
public final class CandidateTracks {

  public static final String FRONTEND = "FRONTEND";
  public static final String BACKEND = "BACKEND";

  private static final String FRONTEND_SKILL_ID = "frontend";
  private static final String DEFAULT_BACKEND_SKILL_ID = "java-backend";

  private static final Set<String> ALLOWED = Set.of(FRONTEND, BACKEND);

  private static final Set<String> BACKEND_PRESET_SKILLS = Set.of(
      "java-backend",
      "java-backend-tencent",
      "ali-backend",
      "bytedance-backend",
      "python-backend"
  );

  private static final Set<String> PURE_BACKEND_CATEGORY_KEYS = Set.of(
      "JAVA", "JVM", "SPRING", "SPRING_BOOT", "SPRING_CLOUD", "MYBATIS",
      "MYSQL", "DATABASE", "SQL", "POSTGRESQL", "REDIS", "KAFKA", "MQ", "RABBITMQ", "ROCKETMQ",
      "PYTHON", "DJANGO", "FASTAPI", "GOLANG", "GO", "MICROSERVICE", "DISTRIBUTED", "BACKEND"
  );

  private static final Set<String> PURE_FRONTEND_CATEGORY_KEYS = Set.of(
      "FRONTEND", "HTML", "CSS", "HTML_CSS", "JAVASCRIPT", "JS", "TYPESCRIPT", "TS",
      "REACT", "VUE", "REACT_VUE", "ANGULAR", "BROWSER", "DOM", "WEBPACK", "VITE", "MINI_PROGRAM"
  );

  private CandidateTracks() {
  }

  /**
   * 校验并规范化输入；为空或空白返回 null，非法值抛出 BAD_REQUEST
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
          "技术方向偏好只能选择偏向前端（FRONTEND）或偏向后端（BACKEND）");
    }
    return normalized;
  }

  /**
   * 安全规范化（用于读取历史数据）：非法或空白返回 null
   */
  public static String safeNormalize(String raw) {
    if (raw == null) {
      return null;
    }
    String normalized = raw.trim().toUpperCase(Locale.ROOT);
    return ALLOWED.contains(normalized) ? normalized : null;
  }

  public static String label(String track) {
    String normalized = safeNormalize(track);
    if (normalized == null) {
      return null;
    }
    return switch (normalized) {
      case FRONTEND -> "偏向前端";
      case BACKEND -> "偏向后端";
      default -> null;
    };
  }

  public static String labelOrDefault(String track) {
    String text = label(track);
    return text != null ? text : "不限（按岗位 JD 与简历综合判断）";
  }

  /**
   * 面向 AI 简历初筛的方向评估要求
   */
  public static String screeningGuidance(String track) {
    String normalized = safeNormalize(track);
    if (FRONTEND.equals(normalized)) {
      return "候选人投递时明确选择【偏向前端】：请以【前端工程能力】作为核心筛选依据（如 HTML/CSS、JavaScript/TypeScript、React/Vue 等现代前端框架、状态管理、浏览器原理与网络协议、前端工程化与页面性能优化、前端项目或全栈项目中的前端职责）；若岗位 JD 同时包含前后端或全栈要求，不因后端经验较浅而扣分，但若简历完全缺乏前端技术栈与相关实践则应如实判定匹配度不足。";
    }
    if (BACKEND.equals(normalized)) {
      return "候选人投递时明确选择【偏向后端】：请以【后端工程能力】作为核心筛选依据（如 Java/Python/Go 等服务端语言与并发基础、Spring 等主流后端框架、MySQL/关系型数据库与 SQL/索引/事务、Redis 缓存、消息队列、接口与服务架构设计、后端项目或全栈项目中的服务端职责）；若岗位 JD 同时包含前后端或全栈要求，不因前端页面开发经验较浅而扣分，但若简历完全缺乏后端技术栈与相关实践则应如实判定匹配度不足。";
    }
    return "候选人未指定前后端偏好：请直接对照岗位 JD 要求与简历内容客观评估匹配度。";
  }

  /**
   * 面向 AI 在线笔试的方向命题要求
   */
  public static String writtenQuestionGuidance(String track) {
    String normalized = safeNormalize(track);
    if (FRONTEND.equals(normalized)) {
      return "候选人投递时已选择【偏向前端】：整套笔试必须围绕【前端技术方向】命题（占比不少于 80%，其余可考察 HTTP/计算机网络/通用工程基础），重点覆盖 JavaScript/TypeScript 核心机制（作用域闭包、原型链、事件循环、Promise/异步、类型系统）、HTML5/CSS3 布局与渲染、React/Vue 组件与响应式原理、浏览器渲染管线/缓存/跨域安全（CORS/XSS/CSRF）及前端工程化与性能优化；禁止整卷出 JVM、MySQL 锁与事务内核、Spring 底层等纯后端题目。";
    }
    if (BACKEND.equals(normalized)) {
      return "候选人投递时已选择【偏向后端】：整套笔试必须围绕【后端技术方向】命题（占比不少于 80%，其余可考察 HTTP/数据结构/通用工程基础），重点覆盖服务端语言核心与并发编程、关系型数据库（MySQL 索引、事务隔离级别、锁与 MVCC、SQL 优化）、Redis 缓存（数据结构、持久化、穿透/击穿/雪崩）、主流后端框架（如 Spring IoC/AOP/事务传播）与分布式/接口设计实践；禁止整卷出 CSS 布局细节、虚拟 DOM Diff、浏览器重排重绘等纯前端题目。";
    }
    return "按岗位 JD 与考察维度均衡命题。";
  }

  /**
   * 面向文字面试 / 语音面试的方向提问与简历核验要求
   */
  public static String interviewGuidance(String track) {
    String normalized = safeNormalize(track);
    if (FRONTEND.equals(normalized)) {
      return "【候选人投递技术方向偏好：偏向前端】\n"
          + "候选人在投递简历时已明确选择【偏向前端】。本场面试的技术提问与简历项目追问必须聚焦【前端研发方向】（JavaScript/TypeScript 核心机制、React/Vue 框架与状态管理、浏览器渲染与网络缓存、前端工程化构建、复杂交互组件设计、FCP/LCP/包体积等前端性能优化，以及候选人简历项目中的前端实现细节），避免偏离去深挖纯后端底层机制，同时重点核验其简历中前端经历的真实性。";
    }
    if (BACKEND.equals(normalized)) {
      return "【候选人投递技术方向偏好：偏向后端】\n"
          + "候选人在投递简历时已明确选择【偏向后端】。本场面试的技术提问与简历项目追问必须聚焦【后端研发方向】（服务端语言核心与并发机制、Spring/后端框架原理、MySQL 索引/事务/SQL 调优、Redis 缓存与分布式协调、消息队列、接口幂等与高并发高可用架构设计，以及候选人简历项目中的服务端实现与故障排查细节），避免偏离去深挖纯前端 UI/CSS 细节，同时重点核验其简历中后端经历的真实性。";
    }
    return "";
  }

  /**
   * 根据岗位默认 skillId 与候选人投递时选择的前后端方向，解析实际生效的面试 skillId：
   * - 偏向前端且岗位配置的是预设后端 skill（如默认 java-backend）时，自动切换为 frontend；
   * - 偏向后端且岗位配置的是 frontend 时，自动切换为 java-backend；
   * - custom 或通用 skill（如 ai-agent-dev / algorithm / system-design / test-development）保持原 skillId，配合方向提示词引导。
   */
  public static String resolveEffectiveSkillId(String jobSkillId, String track) {
    String normalized = safeNormalize(track);
    if (normalized == null || jobSkillId == null || jobSkillId.isBlank()) {
      return jobSkillId;
    }
    if (FRONTEND.equals(normalized) && BACKEND_PRESET_SKILLS.contains(jobSkillId)) {
      return FRONTEND_SKILL_ID;
    }
    if (BACKEND.equals(normalized) && FRONTEND_SKILL_ID.equals(jobSkillId)) {
      return DEFAULT_BACKEND_SKILL_ID;
    }
    return jobSkillId;
  }

  /**
   * 过滤岗位考察维度：当岗位同时包含前后端维度（或与候选人所选方向冲突）时，剔除相反方向的纯专属维度；
   * 若过滤后为空，返回空列表交由上层按候选人所选方向自主命题或回退对应预设方向。
   */
  public static List<CategoryDTO> filterCategoriesForTrack(List<CategoryDTO> categories, String track) {
    String normalized = safeNormalize(track);
    if (normalized == null || categories == null || categories.isEmpty()) {
      return categories;
    }
    return categories.stream()
        .filter(c -> c != null && !isOppositeCategory(c, normalized))
        .toList();
  }

  private static boolean isOppositeCategory(CategoryDTO category, String normalizedTrack) {
    String key = category.key() == null ? "" : category.key().trim().toUpperCase(Locale.ROOT);
    String label = category.label() == null ? "" : category.label().trim().toLowerCase(Locale.ROOT);
    if (FRONTEND.equals(normalizedTrack)) {
      if (PURE_BACKEND_CATEGORY_KEYS.contains(key)) {
        return true;
      }
      return label.contains("java") || label.contains("jvm") || label.contains("spring")
          || label.contains("mysql") || label.contains("后端") || label.contains("mybatis");
    }
    if (BACKEND.equals(normalizedTrack)) {
      if (PURE_FRONTEND_CATEGORY_KEYS.contains(key)) {
        return true;
      }
      return label.contains("css") || label.contains("html") || label.contains("react")
          || label.contains("vue") || label.contains("前端") || label.contains("浏览器渲染")
          || label.contains("dom");
    }
    return false;
  }
}
