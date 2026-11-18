package interview.guide.modules.recruitment.model;

import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * 候选人可见的简历初筛未通过原因反馈（脱敏：不含具体得分与分数线）
 */
public record CandidateScreeningFeedbackDTO(
    String summary,
    List<String> missingRequirements
) {

  public static final String DEFAULT_SUMMARY = "您的简历中呈现的技术栈或项目经历与当前岗位核心要求匹配度暂未达到初筛标准。";

  /**
   * 从初筛结果 JSON 提取候选人友好的未通过原因；JSON 为空或解析异常时返回礼貌兜底文案
   */
  public static CandidateScreeningFeedbackDTO fromJson(String screeningResultJson, ObjectMapper objectMapper) {
    if (screeningResultJson != null && !screeningResultJson.isBlank() && objectMapper != null) {
      try {
        ScreeningResult result = objectMapper.readValue(screeningResultJson, ScreeningResult.class);
        if (result != null) {
          return fromResult(result);
        }
      } catch (Exception ignored) {
        // 解析失败回退默认文案，不影响候选人查询与通知
      }
    }
    return new CandidateScreeningFeedbackDTO(DEFAULT_SUMMARY, List.of());
  }

  public static CandidateScreeningFeedbackDTO fromResult(ScreeningResult result) {
    if (result == null) {
      return new CandidateScreeningFeedbackDTO(DEFAULT_SUMMARY, List.of());
    }
    String summary = result.summary() != null && !result.summary().isBlank()
        ? result.summary().trim()
        : DEFAULT_SUMMARY;
    List<String> source = result.missingRequirements() != null && !result.missingRequirements().isEmpty()
        ? result.missingRequirements()
        : (result.risks() != null ? result.risks() : List.of());
    List<String> gaps = source.stream()
        .filter(item -> item != null && !item.isBlank())
        .map(String::trim)
        .distinct()
        .limit(5)
        .toList();
    return new CandidateScreeningFeedbackDTO(summary, gaps);
  }
}
