package interview.guide.modules.recruitment.model;

/**
 * 投递单异步任务类型
 */
public enum ApplicationTaskType {
  /** AI 简历初筛（投递状态 SCREENING） */
  SCREENING(ApplicationStatus.SCREENING),
  /** AI 综合决策（投递状态 AI_REVIEWING） */
  DECISION(ApplicationStatus.AI_REVIEWING);

  private final ApplicationStatus requiredStatus;

  ApplicationTaskType(ApplicationStatus requiredStatus) {
    this.requiredStatus = requiredStatus;
  }

  public ApplicationStatus requiredStatus() {
    return requiredStatus;
  }
}
