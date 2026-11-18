package interview.guide.modules.recruitment.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.log.ErrorLogSanitizer;
import interview.guide.modules.recruitment.model.FillBlankJudgement;
import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionResult;
import interview.guide.modules.recruitment.model.WrittenQuestionType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * 笔试自动判分（不在事务中调用）
 * <ul>
 *   <li>单选 / 多选：与标准答案集合完全一致才得分（多选少选、错选均不得分）</li>
 *   <li>填空：先做规范化精确匹配（忽略大小写、全半角、空白与末尾标点）；
 *       匹配不上的非空答案批量交给 AI 判断是否与标准答案语义等价；AI 调用失败时按精确匹配结果计分</li>
 * </ul>
 * 每题等分，总分 0-100。
 */
@Service
public class WrittenTestGrader {

  private static final Logger log = LoggerFactory.getLogger(WrittenTestGrader.class);
  static final int MAX_CANDIDATE_ANSWER_CHARS = 200;

  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final BeanOutputConverter<FillBlankJudgement> outputConverter;

  public WrittenTestGrader(LlmProviderRegistry llmProviderRegistry,
                           StructuredOutputInvoker structuredOutputInvoker,
                           ResourceLoader resourceLoader) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.systemPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/written-fill-judge-system.st").getContentAsString(StandardCharsets.UTF_8));
    this.userPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/written-fill-judge-user.st").getContentAsString(StandardCharsets.UTF_8));
    this.outputConverter = new BeanOutputConverter<>(FillBlankJudgement.class);
  }

  /**
   * 判分结果
   */
  public record GradeResult(List<WrittenQuestionResult> results, int score, int correctCount) {
  }

  public GradeResult grade(List<WrittenQuestion> questions, Map<Integer, List<String>> answers, String llmProvider) {
    Map<Integer, List<String>> safeAnswers = answers == null ? Map.of() : answers;
    List<WrittenQuestionResult> results = new ArrayList<>();
    List<WrittenQuestion> pendingFill = new ArrayList<>();
    for (WrittenQuestion q : questions) {
      List<String> answer = safeAnswers.getOrDefault(q.index(), List.of());
      if (q.type() == WrittenQuestionType.FILL_BLANK) {
        String text = fillAnswer(answer);
        if (text.isEmpty()) {
          results.add(new WrittenQuestionResult(q.index(), false, List.of(), WrittenQuestionResult.UNANSWERED, null));
        } else if (matchesExactly(text, q.answers())) {
          results.add(new WrittenQuestionResult(q.index(), true, List.of(text), WrittenQuestionResult.RULE, null));
        } else {
          pendingFill.add(q);
          // 先占位，AI 判定后替换
          results.add(new WrittenQuestionResult(q.index(), false, List.of(text), WrittenQuestionResult.RULE, null));
        }
      } else {
        List<String> letters = normalizeLetters(answer, q.options() == null ? 0 : q.options().size());
        if (letters.isEmpty()) {
          results.add(new WrittenQuestionResult(q.index(), false, List.of(), WrittenQuestionResult.UNANSWERED, null));
        } else {
          boolean correct = letters.equals(new ArrayList<>(new TreeSet<>(q.answers())));
          results.add(new WrittenQuestionResult(q.index(), correct, letters, WrittenQuestionResult.RULE, null));
        }
      }
    }
    if (!pendingFill.isEmpty()) {
      applyAiJudgement(pendingFill, safeAnswers, results, llmProvider);
    }
    int correctCount = (int) results.stream().filter(WrittenQuestionResult::correct).count();
    int score = questions.isEmpty() ? 0 : (int) Math.round(correctCount * 100.0 / questions.size());
    return new GradeResult(List.copyOf(results), score, correctCount);
  }

  private void applyAiJudgement(List<WrittenQuestion> pending, Map<Integer, List<String>> answers,
                                List<WrittenQuestionResult> results, String llmProvider) {
    Map<Integer, FillBlankJudgement.Item> judged = new HashMap<>();
    String failure = null;
    try {
      FillBlankJudgement judgement = invokeJudge(pending, answers, llmProvider);
      if (judgement != null && judgement.items() != null) {
        for (FillBlankJudgement.Item item : judgement.items()) {
          if (item != null) {
            judged.put(item.index(), item);
          }
        }
      }
    } catch (Exception e) {
      failure = ErrorLogSanitizer.summarize(e);
      log.warn("AI 填空题判分失败，按精确匹配计分: error={}", failure);
    }
    for (WrittenQuestion q : pending) {
      int pos = indexOfResult(results, q.index());
      WrittenQuestionResult placeholder = results.get(pos);
      FillBlankJudgement.Item item = judged.get(q.index());
      if (item == null) {
        results.set(pos, new WrittenQuestionResult(q.index(), false, placeholder.answer(),
            WrittenQuestionResult.AI_FALLBACK, "AI 判定未完成，按标准答案精确匹配计分"));
      } else {
        String reason = item.reason() == null ? null
            : item.reason().length() > 80 ? item.reason().substring(0, 80) : item.reason();
        results.set(pos, new WrittenQuestionResult(q.index(), item.correct(), placeholder.answer(),
            WrittenQuestionResult.AI, reason));
      }
    }
  }

  private FillBlankJudgement invokeJudge(List<WrittenQuestion> pending, Map<Integer, List<String>> answers,
                                         String llmProvider) {
    StringBuilder sb = new StringBuilder();
    for (WrittenQuestion q : pending) {
      sb.append("### 题号 ").append(q.index()).append('\n');
      sb.append("题干：").append(q.question()).append('\n');
      sb.append("标准答案（任一即可）：").append(String.join(" / ", q.answers())).append('\n');
      sb.append("---候选人答案开始---\n").append(fillAnswer(answers.get(q.index()))).append("\n---候选人答案结束---\n\n");
    }
    String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
    String userPrompt = userPromptTemplate.render(Map.of("items", sb.toString().trim()));
    ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(llmProvider);
    return structuredOutputInvoker.invoke(chatClient, systemPrompt, userPrompt, outputConverter,
        ErrorCode.WRITTEN_TEST_JUDGE_FAILED, "AI 填空题判分失败：", "AI填空判分", log);
  }

  private static int indexOfResult(List<WrittenQuestionResult> results, int index) {
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i).index() == index) {
        return i;
      }
    }
    throw new IllegalStateException("missing result for question " + index);
  }

  /** 填空题取第一项作答，截断过长内容 */
  static String fillAnswer(List<String> answer) {
    if (answer == null || answer.isEmpty() || answer.get(0) == null) {
      return "";
    }
    String s = answer.get(0).trim();
    return s.length() > MAX_CANDIDATE_ANSWER_CHARS ? s.substring(0, MAX_CANDIDATE_ANSWER_CHARS) : s;
  }

  /** 选项字母规范化：大写、去重、排序，丢弃越界字母 */
  static List<String> normalizeLetters(List<String> answer, int optionCount) {
    TreeSet<String> letters = new TreeSet<>();
    if (answer != null) {
      for (String a : answer) {
        if (a == null) {
          continue;
        }
        String s = a.trim().toUpperCase(Locale.ROOT);
        if (s.length() == 1 && s.charAt(0) >= 'A' && s.charAt(0) < 'A' + optionCount) {
          letters.add(s);
        }
      }
    }
    return new ArrayList<>(letters);
  }

  static boolean matchesExactly(String answer, List<String> accepted) {
    String normalized = normalizeText(answer);
    if (normalized.isEmpty() || accepted == null) {
      return false;
    }
    return accepted.stream().anyMatch(a -> normalizeText(a).equals(normalized));
  }

  /** NFKC（全角转半角）、小写、去空白、去首尾引号与末尾标点 */
  static String normalizeText(String text) {
    if (text == null) {
      return "";
    }
    String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    s = s.replaceAll("\\s+", "");
    s = s.replaceAll("^[\"'“”‘’《》<>]+|[\"'“”‘’《》<>]+$", "");
    s = s.replaceAll("[.,;:!?。，；：！？、]+$", "");
    return s;
  }
}
