package interview.guide.modules.recruitment.service;

import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 笔试题目 / 作答 / 判分结果的 JSON 序列化
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WrittenTestCodec {

  private static final TypeReference<List<WrittenQuestion>> QUESTIONS = new TypeReference<>() {
  };
  private static final TypeReference<Map<Integer, List<String>>> ANSWERS = new TypeReference<>() {
  };
  private static final TypeReference<List<WrittenQuestionResult>> RESULTS = new TypeReference<>() {
  };

  private final ObjectMapper objectMapper;

  public String write(Object value) {
    return value == null ? null : objectMapper.writeValueAsString(value);
  }

  public List<WrittenQuestion> readQuestions(String json) {
    return read(json, QUESTIONS, List.of());
  }

  public Map<Integer, List<String>> readAnswers(String json) {
    Map<Integer, List<String>> answers = read(json, ANSWERS, Map.of());
    return answers == null ? Map.of() : new LinkedHashMap<>(answers);
  }

  public List<WrittenQuestionResult> readResults(String json) {
    return read(json, RESULTS, List.of());
  }

  private <T> T read(String json, TypeReference<T> type, T fallback) {
    if (json == null || json.isBlank()) {
      return fallback;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception e) {
      log.warn("解析笔试 JSON 失败: {}", e.getMessage());
      return fallback;
    }
  }
}
