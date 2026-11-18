package interview.guide.modules.recruitment.model;

/**
 * 候选人可见的投递阶段（不暴露分数与内部审核细节）
 */
public enum CandidateStage {
  /** 简历筛选中 */
  SCREENING,
  /** 待完成在线面试 */
  INTERVIEW_PENDING,
  /** 在线面试进行中 */
  INTERVIEWING,
  /** 面试已提交，评估审核中 */
  UNDER_REVIEW,
  /** 已通过，等待安排线下面试 */
  PASSED,
  /** 已收到线下面试邀约 */
  OFFLINE_INVITED,
  /** 已确认线下面试 */
  OFFLINE_CONFIRMED,
  /** 未通过 */
  REJECTED,
  /** 面试邀请已过期 */
  EXPIRED
}
