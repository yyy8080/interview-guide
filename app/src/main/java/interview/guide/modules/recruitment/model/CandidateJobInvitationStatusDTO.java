package interview.guide.modules.recruitment.model;

import java.time.LocalDateTime;

/**
 * 候选人在指定岗位下的受邀准入状态（供投递页展示与门禁提示）
 */
public record CandidateJobInvitationStatusDTO(
    boolean inviteOnly,
    boolean canApply,
    boolean invited,
    boolean expired,
    boolean revoked,
    boolean skipScreening,
    LocalDateTime expiresAt,
    String message
) {
}
