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
import interview.guide.modules.recruitment.model.ProctorRiskAssessment;
import interview.guide.modules.recruitment.model.ScreeningResult;
import interview.guide.modules.recruitment.model.WrittenTestSummary;
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
 * AI 综合决策：综合分数线规则（含笔试加权）+ LLM 综合判断。
 * <p>
 * 最终结论 = 综合分 ≥ 通过线 且 不存在「LLM 判定 REJECT 且置信度 ≥ 阈值」；
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

  public AiReviewRecord decide(ApplicationEntity application, JobPositionEntity job, ScreeningResult screening,
                               EvaluationSnapshot snapshot, Map<String, Long> proctorCounts,
                               ProctorRiskAssessment proctorRisk, boolean skipLlm) {
    return decide(application, job, screening, snapshot, proctorCounts, proctorRisk, null, skipLlm);
  }

  /**
   * 生成审核记录（不在事务中调用）
   *
   * @param written 在线笔试摘要（未启用或未进行笔试时为空）
   * @param skipLlm 为 true 时只按分数线判定（恢复兜底）
   */
  public AiReviewRecord decide(ApplicationEntity application, JobPositionEntity job, ScreeningResult screening,
                               EvaluationSnapshot snapshot, Map<String, Long> proctorCounts,
                               ProctorRiskAssessment proctorRisk, WrittenTestSummary written, boolean skipLlm) {
    Integer score = snapshot.score();
    int threshold = job.getAiPassThreshold() == null ? 60 : job.getAiPassThreshold();
    if (score == null) {
      return new AiReviewRecord(null, null, threshold, null, true, "缺少面试评估分数，转人工审核", proctorCounts,
          proctorRisk, written);
    }
    int effectiveScore = written != null ? written.finalScore() : score;
    DecisionResult llm = null;
    String note = null;
    if (!skipLlm) {
      try {
        llm = invokeLlm(application, job, screening, snapshot, threshold, proctorCounts, proctorRisk, written);
      } catch (Exception e) {
        log.warn("AI 综合决策 LLM 调用失败，按分数线规则判定: applicationId={}, error={}",
            application.getId(), ErrorLogSanitizer.summarize(e));
        note = "LLM 综合判断失败，按分数线规则判定";
      }
    } else {
      note = "按分数线规则判定";
    }
    AiDecision decision = combine(effectiveScore, threshold, llm, properties.getRejectConfidenceThreshold());
    return new AiReviewRecord(decision, score, threshold, llm, llm == null, note, proctorCounts, proctorRisk, written);
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
                                   EvaluationSnapshot snapshot, int threshold, Map<String, Long> proctorCounts,
                                   ProctorRiskAssessment proctorRisk, WrittenTestSummary written) {
    String effectiveLevel = interview.guide.modules.recruitment.model.CandidateLevels.resolve(
        application.getCandidateLevel(), job.getDifficulty());
    Map<String, Object> variables = new HashMap<>();
    variables.put("jobTitle", job.getTitle());
    variables.put("jobDifficulty", interview.guide.modules.recruitment.model.CandidateLevels.label(job.getDifficulty()));
    variables.put("candidateTrack", interview.guide.modules.recruitment.model.CandidateTracks.labelOrDefault(
        application.getCandidateTrack()));
    variables.put("candidateLevel", interview.guide.modules.recruitment.model.CandidateLevels.label(effectiveLevel));
    variables.put("candidateSupplement", interview.guide.modules.recruitment.model.CandidateAiExperiences.describeForPrompt(
        application.getAiExperience(), application.getSpecialties()));
    variables.put("passThreshold", threshold);
    variables.put("jdText", ResumeScreeningService.truncate(job.getJdText(), 6_000));
    variables.put("screeningSummary", describeScreening(application, screening));
    variables.put("writtenSummary", describeWritten(written));
    variables.put("interviewMode", application.getInterviewMode() == null
        ? "文字" : application.getInterviewMode().name().equals("VOICE") ? "语音" : "文字");
    variables.put("interviewScore", snapshot.score());
    variables.put("evaluationText", snapshot.evaluationText() == null || snapshot.evaluationText().isBlank()
        ? "（无详细评估）" : snapshot.evaluationText());
    variables.put("proctorSummary", describeProctor(proctorCounts, proctorRisk));
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

  static String describeWritten(WrittenTestSummary written) {
    if (written == null) {
      return "（未启用或未进行在线笔试）";
    }
    return "笔试得分：" + written.writtenScore() + "/100（答对 " + written.correctCount() + "/" + written.totalCount()
        + " 题，权重 " + written.weight() + "%）\n"
        + "加权综合分：" + written.finalScore() + "/100（笔试 " + written.writtenScore() + "×" + written.weight()
        + "% + 面试 " + written.interviewScore() + "×" + (100 - written.weight()) + "%）";
  }

  private static String describeScreening(ApplicationEntity application, ScreeningResult screening) {
    StringBuilder sb = new StringBuilder();
    if (application != null && application.getResumeRevisionCount() > 0) {
      sb.append("简历重提记录：候选人曾在首次初筛未通过后修改重传过 ")
          .append(application.getResumeRevisionCount())
          .append(" 次简历，请重点结合面试回答核验新补充经历的真实性。\n");
    }
    if (screening == null) {
      sb.append("（未进行 AI 初筛）");
      return sb.toString();
    }
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
    return describeProctor(counts, null);
  }

  /**
   * 作答行为摘要：风险分 + 风险说明 + 事件计数。仅作为参考信号提供给 LLM。
   */
  static String describeProctor(Map<String, Long> counts, ProctorRiskAssessment risk) {
    boolean noCounts = counts == null || counts.isEmpty();
    boolean noFindings = risk == null || risk.findings() == null || risk.findings().isEmpty();
    if (noCounts && noFindings) {
      return "无异常行为记录";
    }
    StringBuilder sb = new StringBuilder();
    if (risk != null) {
      sb.append("行为风险分：").append(risk.score()).append("/100（").append(riskLabel(risk)).append("）\n");
      if (!noFindings) {
        risk.findings().stream().limit(8).forEach(f -> sb.append("- ").append(f).append('\n'));
      }
    }
    if (!noCounts) {
      sb.append("事件计数：");
      counts.forEach((type, count) -> sb.append(label(type)).append(' ').append(count).append(" 次；"));
    }
    return sb.toString().trim();
  }

  private static String riskLabel(ProctorRiskAssessment risk) {
    if (risk.level() == null) {
      return "未知";
    }
    return switch (risk.level()) {
      case LOW -> "低风险";
      case MEDIUM -> "中风险";
      case HIGH -> "高风险";
    };
  }

  private static String label(String type) {
    return switch (type) {
      case "TAB_HIDDEN" -> "切换标签页/最小化";
      case "WINDOW_BLUR" -> "窗口失焦";
      case "PASTE" -> "粘贴";
      case "COPY" -> "复制";
      case "FULLSCREEN_EXIT" -> "退出全屏";
      case "VIEWPORT_SHRINK" -> "窗口明显缩小";
      case "MULTI_SCREEN" -> "扩展显示器";
      case "VOICE_TEXT_EDITED" -> "语音回答大幅改写";
      case "VOICE_SLOW_RESPONSE" -> "语音长时间未应答";
      default -> type;
    };
  }
}
