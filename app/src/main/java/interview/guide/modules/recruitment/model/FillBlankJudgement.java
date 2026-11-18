package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * LLM 填空题语义判定结果
 */
public record FillBlankJudgement(List<Item> items) {

  /**
   * @param index   题号
   * @param correct 候选人答案是否与标准答案语义等价
   * @param reason  判定理由（不超过 40 字）
   */
  public record Item(int index, boolean correct, String reason) {
  }
}
