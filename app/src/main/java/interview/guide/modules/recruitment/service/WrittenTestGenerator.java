package interview.guide.modules.recruitment.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.GeneratedWrittenTest;
import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

/**
 * AI 笔试出题：按岗位 JD 为每位候选人实时生成单选 / 多选 / 填空题（不在事务中调用）。
 * <p>
 * LLM 输出会经过严格校验：选项数量、正确答案个数、空位标记等不合规的题目直接丢弃；
 * 选择题选项顺序随机打乱，降低候选人之间互通答案的可能。
 */
@Service
public class WrittenTestGenerator {

  private static final Logger log = LoggerFactory.getLogger(WrittenTestGenerator.class);
  private static final int MAX_JD_CHARS = 8_000;
  static final int MAX_QUESTION_CHARS = 500;
  static final int MAX_OPTION_CHARS = 200;
  static final int MAX_FILL_ANSWER_CHARS = 100;
  static final int MAX_FILL_ANSWERS = 8;
  static final String BLANK = "____";
  private static final Pattern BLANK_PATTERN = Pattern.compile("_{3,}|＿{2,}|（\\s*）|\\(\\s*\\)");
  private static final Pattern OPTION_PREFIX = Pattern.compile("^\\s*[A-Fa-f]\\s*[.、:：)）]\\s*");
  private static final String LETTERS = "ABCDEF";

  private final LlmProviderRegistry llmProviderRegistry;
  private final StructuredOutputInvoker structuredOutputInvoker;
  private final PromptTemplate systemPromptTemplate;
  private final PromptTemplate userPromptTemplate;
  private final BeanOutputConverter<GeneratedWrittenTest> outputConverter;
  private final Random random = new SecureRandom();

  public WrittenTestGenerator(LlmProviderRegistry llmProviderRegistry,
                              StructuredOutputInvoker structuredOutputInvoker,
                              ResourceLoader resourceLoader) throws IOException {
    this.llmProviderRegistry = llmProviderRegistry;
    this.structuredOutputInvoker = structuredOutputInvoker;
    this.systemPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/written-test-system.st").getContentAsString(StandardCharsets.UTF_8));
    this.userPromptTemplate = new PromptTemplate(resourceLoader
        .getResource("classpath:prompts/written-test-user.st").getContentAsString(StandardCharsets.UTF_8));
    this.outputConverter = new BeanOutputConverter<>(GeneratedWrittenTest.class);
  }

  public List<WrittenQuestion> generate(JobPositionEntity job, List<CategoryDTO> categories) {
    return generate(job, categories, job.getDifficulty());
  }

  /**
   * 按指定应聘定位生成笔试题；LLM 调用失败或没有任何合规题目时抛出 WRITTEN_TEST_GENERATION_FAILED
   */
  public List<WrittenQuestion> generate(JobPositionEntity job, List<CategoryDTO> categories, String difficulty) {
    return generate(job, categories, difficulty, null, null, null);
  }

  /**
   * 按指定应聘定位及候选人补充的 AI 经验/个人特长生成笔试题
   */
  public List<WrittenQuestion> generate(JobPositionEntity job, List<CategoryDTO> categories, String difficulty,
                                        String aiExperience, String specialties) {
    return generate(job, categories, difficulty, null, aiExperience, specialties);
  }

  /**
   * 按候选人投递时选择的前后端方向、应聘定位及补充特长生成笔试题
   */
  public List<WrittenQuestion> generate(JobPositionEntity job, List<CategoryDTO> categories, String difficulty,
                                        String candidateTrack, String aiExperience, String specialties) {
    int single = Math.max(0, job.getWrittenSingleCount());
    int multi = Math.max(0, job.getWrittenMultiCount());
    int fill = Math.max(0, job.getWrittenFillCount());
    String effectiveDifficulty = interview.guide.modules.recruitment.model.CandidateLevels.resolve(
        difficulty, job.getDifficulty());
    List<CategoryDTO> filteredCategories = interview.guide.modules.recruitment.model.CandidateTracks
        .filterCategoriesForTrack(categories, candidateTrack);
    String systemPrompt = systemPromptTemplate.render() + "\n\n" + outputConverter.getFormat();
    Map<String, Object> vars = new java.util.HashMap<>();
    vars.put("jobTitle", job.getTitle());
    vars.put("candidateTrack", interview.guide.modules.recruitment.model.CandidateTracks.labelOrDefault(candidateTrack));
    vars.put("trackGuidance", interview.guide.modules.recruitment.model.CandidateTracks.writtenQuestionGuidance(candidateTrack));
    vars.put("difficulty", difficultyLabel(effectiveDifficulty));
    vars.put("difficultyGuidance", interview.guide.modules.recruitment.model.CandidateLevels.questionGuidance(effectiveDifficulty));
    vars.put("candidateSupplement", interview.guide.modules.recruitment.model.CandidateAiExperiences.describeForPrompt(
        aiExperience, specialties));
    vars.put("jdText", ResumeScreeningService.truncate(job.getJdText(), MAX_JD_CHARS));
    vars.put("categories", describeCategories(filteredCategories, candidateTrack));
    vars.put("singleCount", single);
    vars.put("multiCount", multi);
    vars.put("fillCount", fill);
    String userPrompt = userPromptTemplate.render(vars);
    ChatClient chatClient = llmProviderRegistry.getChatClientOrDefault(job.getLlmProvider());
    GeneratedWrittenTest raw = structuredOutputInvoker.invoke(chatClient, systemPrompt, userPrompt, outputConverter,
        ErrorCode.WRITTEN_TEST_GENERATION_FAILED, "AI 笔试出题失败：", "AI笔试出题", log);
    List<WrittenQuestion> questions = normalize(raw, single, multi, fill, random);
    if (questions.isEmpty()) {
      throw new BusinessException(ErrorCode.WRITTEN_TEST_GENERATION_FAILED);
    }
    int expected = single + multi + fill;
    if (questions.size() < expected) {
      log.warn("AI 笔试出题数量不足: jobId={}, track={}, level={}, expected={}, actual={}",
          job.getId(), candidateTrack, effectiveDifficulty, expected, questions.size());
    }
    return questions;
  }

  /**
   * 校验并规范化 LLM 输出：按 单选 → 多选 → 填空 排列，每类最多取配置数量，重新编号并打乱选项
   */
  static List<WrittenQuestion> normalize(GeneratedWrittenTest raw, int single, int multi, int fill, Random random) {
    Map<WrittenQuestionType, Integer> limits = new EnumMap<>(WrittenQuestionType.class);
    limits.put(WrittenQuestionType.SINGLE_CHOICE, single);
    limits.put(WrittenQuestionType.MULTIPLE_CHOICE, multi);
    limits.put(WrittenQuestionType.FILL_BLANK, fill);
    Map<WrittenQuestionType, List<WrittenQuestion>> buckets = new EnumMap<>(WrittenQuestionType.class);
    for (WrittenQuestionType type : WrittenQuestionType.values()) {
      buckets.put(type, new ArrayList<>());
    }
    Set<String> seen = new LinkedHashSet<>();
    if (raw != null && raw.questions() != null) {
      for (GeneratedWrittenTest.Item item : raw.questions()) {
        WrittenQuestion q = normalizeItem(item, random);
        if (q == null) {
          continue;
        }
        List<WrittenQuestion> bucket = buckets.get(q.type());
        if (bucket.size() >= limits.get(q.type()) || !seen.add(q.question())) {
          continue;
        }
        bucket.add(q);
      }
    }
    List<WrittenQuestion> result = new ArrayList<>();
    for (WrittenQuestionType type : WrittenQuestionType.values()) {
      for (WrittenQuestion q : buckets.get(type)) {
        result.add(new WrittenQuestion(result.size(), q.type(), q.question(), q.options(), q.answers(),
            q.explanation(), q.category()));
      }
    }
    return result;
  }

  static WrittenQuestion normalizeItem(GeneratedWrittenTest.Item item, Random random) {
    if (item == null) {
      return null;
    }
    WrittenQuestionType type = parseType(item.type());
    String question = clean(item.question(), MAX_QUESTION_CHARS);
    if (type == null || question.isEmpty()) {
      return null;
    }
    String explanation = clean(item.explanation(), 300);
    String category = clean(item.category(), 32);
    if (type == WrittenQuestionType.FILL_BLANK) {
      return normalizeFill(question, item.answers(), explanation, category);
    }
    return normalizeChoice(type, question, item.options(), item.answers(), explanation, category, random);
  }

  private static WrittenQuestion normalizeFill(String question, List<String> answers, String explanation,
                                               String category) {
    List<String> accepted = new ArrayList<>();
    if (answers != null) {
      for (String a : answers) {
        String s = clean(a, MAX_FILL_ANSWER_CHARS);
        if (!s.isEmpty() && !accepted.contains(s) && accepted.size() < MAX_FILL_ANSWERS) {
          accepted.add(s);
        }
      }
    }
    if (accepted.isEmpty()) {
      return null;
    }
    String normalizedQuestion = BLANK_PATTERN.matcher(question).replaceAll(BLANK);
    int blanks = countBlanks(normalizedQuestion);
    if (blanks > 1) {
      // 只支持单空：多空题无法可靠判分，丢弃
      return null;
    }
    if (blanks == 0) {
      normalizedQuestion = normalizedQuestion + "：" + BLANK;
    }
    // 题干中直接出现答案的题目没有考察价值
    String lowerQuestion = normalizedQuestion.toLowerCase(Locale.ROOT);
    if (accepted.stream().anyMatch(a -> a.length() >= 2 && lowerQuestion.contains(a.toLowerCase(Locale.ROOT)))) {
      return null;
    }
    return new WrittenQuestion(0, WrittenQuestionType.FILL_BLANK, normalizedQuestion, List.of(), List.copyOf(accepted),
        explanation, category);
  }

  private static WrittenQuestion normalizeChoice(WrittenQuestionType type, String question, List<String> rawOptions,
                                                 List<String> rawAnswers, String explanation, String category,
                                                 Random random) {
    if (rawOptions == null) {
      return null;
    }
    List<String> options = new ArrayList<>();
    for (String o : rawOptions) {
      String s = clean(o == null ? null : OPTION_PREFIX.matcher(o).replaceFirst(""), MAX_OPTION_CHARS);
      if (s.isEmpty() || options.contains(s)) {
        return null;
      }
      options.add(s);
    }
    boolean single = type == WrittenQuestionType.SINGLE_CHOICE;
    int minOptions = single ? 3 : 4;
    if (options.size() < minOptions || options.size() > LETTERS.length()) {
      return null;
    }
    Set<Integer> correct = new LinkedHashSet<>();
    if (rawAnswers != null) {
      for (String a : rawAnswers) {
        Integer idx = answerIndex(a, options);
        if (idx == null) {
          return null;
        }
        correct.add(idx);
      }
    }
    if (single ? correct.size() != 1 : correct.size() < 2) {
      return null;
    }
    // 打乱选项顺序并重映射正确答案
    List<Integer> order = new ArrayList<>();
    for (int i = 0; i < options.size(); i++) {
      order.add(i);
    }
    Collections.shuffle(order, random);
    List<String> shuffled = new ArrayList<>();
    List<String> answers = new ArrayList<>();
    for (int pos = 0; pos < order.size(); pos++) {
      int original = order.get(pos);
      shuffled.add(options.get(original));
      if (correct.contains(original)) {
        answers.add(String.valueOf(LETTERS.charAt(pos)));
      }
    }
    Collections.sort(answers);
    return new WrittenQuestion(0, type, question, List.copyOf(shuffled), List.copyOf(answers), explanation, category);
  }

  /** 正确答案可能是字母（A）、带前缀的字母（A.）或选项原文 */
  private static Integer answerIndex(String answer, List<String> options) {
    if (answer == null) {
      return null;
    }
    String s = answer.trim();
    if (s.isEmpty()) {
      return null;
    }
    String letter = s.replaceAll("[.、:：)）\\s]", "").toUpperCase(Locale.ROOT);
    if (letter.length() == 1) {
      int idx = LETTERS.indexOf(letter.charAt(0));
      return idx >= 0 && idx < options.size() ? idx : null;
    }
    String text = OPTION_PREFIX.matcher(s).replaceFirst("").trim();
    int idx = options.indexOf(text);
    return idx >= 0 ? idx : null;
  }

  static WrittenQuestionType parseType(String type) {
    if (type == null) {
      return null;
    }
    String t = type.trim().toUpperCase(Locale.ROOT);
    if (t.contains("MULTI") || t.contains("多选")) {
      return WrittenQuestionType.MULTIPLE_CHOICE;
    }
    if (t.contains("SINGLE") || t.contains("单选")) {
      return WrittenQuestionType.SINGLE_CHOICE;
    }
    if (t.contains("FILL") || t.contains("BLANK") || t.contains("填空")) {
      return WrittenQuestionType.FILL_BLANK;
    }
    return null;
  }

  private static int countBlanks(String question) {
    int count = 0;
    int from = 0;
    while ((from = question.indexOf(BLANK, from)) >= 0) {
      count++;
      from += BLANK.length();
    }
    return count;
  }

  private static String clean(String value, int max) {
    if (value == null) {
      return "";
    }
    String s = value.trim();
    return s.length() > max ? s.substring(0, max) : s;
  }

  private static String difficultyLabel(String difficulty) {
    if (difficulty == null) {
      return "中级";
    }
    return switch (difficulty) {
      case "junior" -> "初级";
      case "senior" -> "高级";
      default -> "中级";
    };
  }

  private static String describeCategories(List<CategoryDTO> categories) {
    return describeCategories(categories, null);
  }

  private static String describeCategories(List<CategoryDTO> categories, String candidateTrack) {
    if (categories == null || categories.isEmpty()) {
      String trackLabel = interview.guide.modules.recruitment.model.CandidateTracks.label(candidateTrack);
      if (trackLabel != null) {
        return "（未限定固定细分维度，请紧扣候选人所选【" + trackLabel + "】方向与岗位 JD 核心要求确定考察重点）";
      }
      return "（未配置，请根据岗位 JD 自行确定考察重点）";
    }
    return categories.stream()
        .filter(c -> c != null && c.label() != null && !c.label().isBlank())
        .limit(20)
        .map(c -> "- " + c.label() + (c.priority() != null ? "（" + c.priority() + "）" : ""))
        .collect(Collectors.joining("\n"));
  }
}
