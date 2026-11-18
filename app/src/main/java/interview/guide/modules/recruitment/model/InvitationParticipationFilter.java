package interview.guide.modules.recruitment.model;

/**
 * 后台邀请列表按参面情况筛选分组
 */
public enum InvitationParticipationFilter {
  PARTICIPATED,
  PENDING_INTERVIEW,
  NOT_APPLIED,
  SCREEN_REJECTED,
  EXPIRED_OR_REVOKED;

  public boolean matches(InvitationParticipationStatus status) {
    return switch (this) {
      case PARTICIPATED -> status == InvitationParticipationStatus.COMPLETED
          || status == InvitationParticipationStatus.IN_INTERVIEW;
      case PENDING_INTERVIEW -> status == InvitationParticipationStatus.AWAITING_INTERVIEW
          || status == InvitationParticipationStatus.SCREENING;
      case NOT_APPLIED -> status == InvitationParticipationStatus.NOT_APPLIED;
      case SCREEN_REJECTED -> status == InvitationParticipationStatus.SCREEN_REJECTED;
      case EXPIRED_OR_REVOKED -> status == InvitationParticipationStatus.EXPIRED
          || status == InvitationParticipationStatus.REVOKED;
    };
  }
}
