package interview.guide.modules.recruitment.model;

/**
 * 后台面试邀请统计概览
 */
public record InvitationStatsDTO(
    long total,
    long participated,
    long pendingInterview,
    long notApplied,
    long screenRejected,
    long expiredOrRevoked
) {
}
