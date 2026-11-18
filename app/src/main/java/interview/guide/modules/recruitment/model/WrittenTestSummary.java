package interview.guide.modules.recruitment.model;

/**
 * AI 审核记录中的笔试摘要
 *
 * @param writtenScore   笔试得分 0-100
 * @param weight         笔试权重（%）
 * @param interviewScore AI 面试得分 0-100
 * @param finalScore     综合分 = 笔试分 × 权重 + 面试分 × (1 - 权重)
 * @param correctCount   答对题数
 * @param totalCount     总题数
 */
public record WrittenTestSummary(
    int writtenScore,
    int weight,
    int interviewScore,
    int finalScore,
    int correctCount,
    int totalCount
) {

  /** 综合分（四舍五入） */
  public static int combine(int writtenScore, int interviewScore, int weight) {
    int w = Math.max(0, Math.min(100, weight));
    return (int) Math.round((writtenScore * w + interviewScore * (100 - w)) / 100.0);
  }
}
