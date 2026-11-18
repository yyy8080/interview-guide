package interview.guide.modules.recruitment.model;

/**
 * 笔试状态
 */
public enum WrittenTestStatus {
  /** 作答中 */
  IN_PROGRESS,
  /** 已交卷，等待判分（判分失败时保持该状态，AI 决策前会再次判分） */
  SUBMITTED,
  /** 已判分 */
  GRADED
}
