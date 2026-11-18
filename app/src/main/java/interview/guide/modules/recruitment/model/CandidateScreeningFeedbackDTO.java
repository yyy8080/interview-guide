package interview.guide.modules.recruitment.model;

import java.util.List;
import tools.jackson.databind.ObjectMapper;

/**
 * 候选人可见的简历初筛未通过原因反馈与简历修改重提资格（脱敏：不含具体得分与分数线）
 */
public record CandidateScreeningFeedbackDTO(
    String summary,
    List<String> missingRequirements,
    boolean canResubmit,
    int revisionCount,
    int maxRevisions
) {

  public static final String DEFAULT_SUMMARY = "您的简历中呈现的技术栈或项目经历与当前岗位核心要求匹配度暂未达到初筛标准。";

  public static CandidateScreeningFeedbackDTO fromJson(String screeningResultJson, ObjectMapper objectMapper) {
    return fromJson(screeningResultJson, objectMapper, false, 0, 1);
  }

  /**
   * 从初筛结果 JSON 提取候选人友好的未通过原因，并附带剩余简历修改重提资格
   */
  public static CandidateScreeningFeedbackDTO fromJson(String screeningResultJson, ObjectMapper objectMapper,
                                                       boolean canResubmit, int revisionCount, int maxRevisions) {
    if (screeningResultJson != null && !screeningResultJson.isBlank() && objectMapper != null) {
      try {
        ScreeningResult result = objectMapper.readValue(screeningResultJson, ScreeningResult.class);
        if (result != null) {
          return fromResult(result, canResubmit, revisionCount, maxRevisions);
        }
      } catch (Exception ignored) {
        // 解析失败回退默认文案，不影响候选人查询与通知
      }
    }
    return new CandidateScreeningFeedbackDTO(DEFAULT_SUMMARY, List.of(), canResubmit, revisionCount, maxRevisions);
  }

  public static CandidateScreeningFeedbackDTO fromResult(ScreeningResult result, boolean canResubmit,
                                                         int revisionCount, int maxRevisions) {
    if (result == null) {
      return new CandidateScreeningFeedbackDTO(DEFAULT_SUMMARY, List.of(), canResubmit, revisionCount, maxRevisions);
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
    return new CandidateScreeningFeedbackDTO(summary, gaps, canResubmit, revisionCount, maxRevisions);
  }
}
