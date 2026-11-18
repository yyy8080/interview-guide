package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 作弊风险评估结果
 *
 * @param score             风险分 0-100
 * @param level             风险等级
 * @param findings          可读的风险说明（按严重程度排序）
 * @param flaggedQuestions  存在可疑行为的题号（从 0 开始）
 */
public record ProctorRiskAssessment(
    int score,
    ProctorRiskLevel level,
    List<String> findings,
    List<Integer> flaggedQuestions
) {

  public static ProctorRiskAssessment empty() {
    return new ProctorRiskAssessment(0, ProctorRiskLevel.LOW, List.of(), List.of());
  }
}
