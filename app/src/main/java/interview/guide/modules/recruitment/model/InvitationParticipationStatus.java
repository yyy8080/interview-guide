package interview.guide.modules.recruitment.model;

/**
 * 受邀候选人参面实时状态（供 HR 后台查看面试者是否参加面试）
 */
public enum InvitationParticipationStatus {
  /** 已参加且已完成在线面试 */
  COMPLETED,
  /** 已参加（在线笔试中 / 已交笔试待面试 / 在线面试进行中） */
  IN_INTERVIEW,
  /** 未参加（已通过简历初筛，待开始在线笔试或面试） */
  AWAITING_INTERVIEW,
  /** 未参加（已投递简历，AI 初筛中） */
  SCREENING,
  /** 未参加（简历初筛未通过或开考前被淘汰） */
  SCREEN_REJECTED,
  /** 未参加（尚未投递简历） */
  NOT_APPLIED,
  /** 未参加（邀请已过期或在线面试逾期未开考） */
  EXPIRED,
  /** 已撤销邀请 */
  REVOKED;

  public boolean isParticipated() {
    return this == COMPLETED || this == IN_INTERVIEW;
  }
}
