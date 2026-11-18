package interview.guide.modules.recruitment.model;

/**
 * 笔试题型
 */
public enum WrittenQuestionType {
  /** 单选题：4 个选项，仅 1 个正确 */
  SINGLE_CHOICE,
  /** 多选题：4-5 个选项，至少 2 个正确，全部选对才得分 */
  MULTIPLE_CHOICE,
  /** 填空题：一个空，支持多个等价标准答案，规则匹配不上时交由 AI 判断语义是否等价 */
  FILL_BLANK
}
