package interview.guide.modules.recruitment.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.AiDecision;
import interview.guide.modules.recruitment.model.AiReviewRecord;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.DecisionResult;
import interview.guide.modules.recruitment.model.ScreeningResult;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluationSnapshot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * AI 综合决策：面试分数线规则 + LLM 综合判断。
 * <p>
 * 最终结论 = 分数 ≥ 通过线 且 不存在「LLM 判定 REJECT 且置信度 ≥ 阈值」；
 * LLM 调用失败时仅按分数线规则判定。AI 判定不通过即为最终不通过。
 */
@Service
public class CandidateDecisionService {

  private static final Logger log = LoggerFactory.getLogger(CandidateDecisionService.class);

  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final RecruitmentProperties properties;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final BeanOutputConverter<DecisionResult> outputConverter;

  public CandidateDecisionService(LlmProviderRegistry llmProviderRegistry,
                                  StructuredOutputInvoker structuredOutputInvoker,
                                  RecruitmentProperties properties,
                                  ResourceLoader resourceLoader) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.properties = properties;
    this.systemPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/candidate-decision-system.st").getContentAsString(StandardCharsets.UTF_8));
    this.userPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/candidate-decision-user.st").getContentAsString(StandardCharsets.UTF_8));
    this.outputConverter = new BeanOutputConverter<>(DecisionResult.class);
  }

  /**
   * 生成审核记录（不在事务中调用）
   *
   * @param skipLlm 为 true 时只按分数线判定（恢复兜底）
   */
  public AiReviewRecord decide(ApplicationEntity application, JobPositionEntity job, ScreeningResult screening,
                               EvaluationSnapshot snapshot, Map<String, Long> proctorCounts, boolean skipLlm) {
    Integer score = snapshot.score();
    int threshold = job.getAiPassThreshold() == null ? 60 : job.getAiPassThreshold();
    if (score == null) {
      return new AiReviewRecord(null, null, threshold, null, true, "缺少面试评估分数，转人工审核", proctorCounts);
    }
    DecisionResult llm = null;
    String note = null;
    if (!skipLlm) {
      try {
        llm = invokeLlm(application, job, screening, snapshot, threshold, proctorCounts);
      } catch (Exception e) {
        log.warn("AI 综合决策 LLM 调用失败，按分数线规则判定: applicationId={}, error={}",
            application.getId(), ErrorLogSanitizer.summarize(e));
        note = "LLM 综合判断失败，按分数线规则判定";
      }
    } else {
      note = "按分数线规则判定";
    }
    AiDecision decision = combine(score, threshold, llm, properties.getRejectConfidenceThreshold());
    return new AiReviewRecord(decision, score, threshold, llm, llm == null, note, proctorCounts);
  }

  /**
   * 决策合并规则
   */
  static AiDecision combine(int score, int threshold, DecisionResult llm, double rejectConfidence) {
    if (score < threshold) {
      return AiDecision.REJECT;
    }
    if (llm != null && "REJECT".equalsIgnoreCase(llm.decision()) && llm.confidence() >= rejectConfidence) {
      return AiDecision.REJECT;
    }
    return AiDecision.PASS;
  }

  private DecisionResult invokeLlm(ApplicationEntity application, JobPositionEntity job, ScreeningResult screening,
                                   EvaluationSnapshot snapshot, int threshold, Map<String, Long> proctorCounts) {
    Map<String, Object> variables = new HashMap<>();
    variables.put("jobTitle", job.getTitle());
    variables.put("passThreshold", threshold);
    variables.put("jdText", ResumeScreeningService.truncate(job.getJdText(), 6_000));
    variables.put("screeningSummary", describeScreening(screening));
    variables.put("interviewMode", application.getInterviewMode() == null
        ? "文字" : application.getInterviewMode().name().equals("VOICE") ? "语音" : "文字");
    variables.put("interviewScore", snapshot.score());
    variables.put("evaluationText", snapshot.evaluationText() == null || snapshot.evaluationText().isBlank()
        ? "（无详细评估）" : snapshot.evaluationText());
    variables.put("proctorSummary", describeProctor(proctorCounts));
    String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
    String userPrompt = userPromptTemplate.render(variables);
    ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(job.getLlmProvider());
    DecisionResult raw = structuredOutputInvoker.invoke(chatClient, systemPrompt, userPrompt, outputConverter,
        ErrorCode.DECISION_FAILED, "AI 综合决策失败：", "AI综合决策", log);
    return normalize(raw);
  }

  static DecisionResult normalize(DecisionResult raw) {
    String decision = raw.decision() == null ? "PASS" : raw.decision().trim().toUpperCase(Locale.ROOT);
    if (!decision.equals("PASS") && !decision.equals("REJECT")) {
      decision = "PASS";
    }
    double confidence = Double.isNaN(raw.confidence()) ? 0 : Math.max(0, Math.min(1, raw.confidence()));
    return new DecisionResult(decision, confidence, raw.summary() == null ? "" : raw.summary(),
        safe(raw.reasons()), safe(raw.risks()), safe(raw.suggestedQuestions()));
  }

  private static List<String> safe(List<String> list) {
    return list == null ? List.of() : list.stream().filter(s -> s != null && !s.isBlank()).limit(8).toList();
  }

  private static String describeScreening(ScreeningResult screening) {
    if (screening == null) {
      return "（未进行 AI 初筛）";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("匹配分：").append(screening.score()).append('\n');
    sb.append("结论：").append(screening.summary()).append('\n');
    if (!screening.missingRequirements().isEmpty()) {
      sb.append("缺失要求：").append(String.join("；", screening.missingRequirements())).append('\n');
    }
    if (!screening.risks().isEmpty()) {
      sb.append("风险：").append(String.join("；", screening.risks()));
    }
    return sb.toString();
  }

  static String describeProctor(Map<String, Long> counts) {
    if (counts == null || counts.isEmpty()) {
      return "无异常行为记录";
    }
    StringBuilder sb = new StringBuilder();
    counts.forEach((type, count) -> sb.append(label(type)).append(' ').append(count).append(" 次；"));
    return sb.toString();
  }

  private static String label(String type) {
    return switch (type) {
      case "TAB_HIDDEN" -> "切换标签页/最小化";
      case "WINDOW_BLUR" -> "窗口失焦";
      case "PASTE" -> "粘贴";
      case "COPY" -> "复制";
      case "FULLSCREEN_EXIT" -> "退出全屏";
      default -> type;
    };
  }
}
