package interview.guide.modules.recruitment.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.ScreeningResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * AI 简历初筛：对照岗位 JD 给简历打匹配分（不在事务中调用）
 */
@Service
public class ResumeScreeningService {

  private static final Logger log = LoggerFactory.getLogger(ResumeScreeningService.class);
  private static final int MAX_RESUME_CHARS = 20_000;
  private static final int MAX_JD_CHARS = 8_000;

  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final BeanOutputConverter<ScreeningResult> outputConverter;

  public ResumeScreeningService(LlmProviderRegistry llmProviderRegistry,
                                StructuredOutputInvoker structuredOutputInvoker,
                                ResourceLoader resourceLoader) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.systemPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/resume-screening-system.st").getContentAsString(StandardCharsets.UTF_8));
    this.userPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/resume-screening-user.st").getContentAsString(StandardCharsets.UTF_8));
    this.outputConverter = new BeanOutputConverter<>(ScreeningResult.class);
  }

  public ScreeningResult screen(JobPositionEntity job, String resumeText) {
    return screen(job, resumeText, null);
  }

  /**
   * 执行初筛；LLM 调用失败抛出 BusinessException(SCREENING_FAILED)，由任务消费者重试 / 兜底
   */
  public ScreeningResult screen(JobPositionEntity job, String resumeText, String candidateLevel) {
    String effectiveLevel = interview.guide.modules.recruitment.model.CandidateLevels.resolve(
        candidateLevel, job.getDifficulty());
    String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
    String userPrompt = userPromptTemplate.render(Map.of(
        "jobTitle", job.getTitle(),
        "candidateLevel", interview.guide.modules.recruitment.model.CandidateLevels.label(effectiveLevel),
        "jdText", truncate(job.getJdText(), MAX_JD_CHARS),
        "resumeText", truncate(resumeText, MAX_RESUME_CHARS)));
    ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(job.getLlmProvider());
    ScreeningResult raw = structuredOutputInvoker.invoke(chatClient, systemPrompt, userPrompt, outputConverter,
        ErrorCode.SCREENING_FAILED, "AI 简历初筛失败：", "AI简历初筛", log);
    return normalize(raw);
  }

  static ScreeningResult normalize(ScreeningResult raw) {
    int score = Math.max(0, Math.min(100, raw.score()));
    return new ScreeningResult(score, raw.summary() == null ? "" : raw.summary(),
        safe(raw.strengths()), safe(raw.risks()), safe(raw.matchedRequirements()),
        safe(raw.missingRequirements()));
  }

  private static List<String> safe(List<String> list) {
    return list == null ? List.of() : list.stream().filter(s -> s != null && !s.isBlank()).limit(8).toList();
  }

  static String truncate(String text, int max) {
    if (text == null) {
      return "";
    }
    return text.length() > max ? text.substring(0, max) + "\n…（已截断）" : text;
  }
}
