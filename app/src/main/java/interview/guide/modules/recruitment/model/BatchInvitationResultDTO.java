package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 批量发送面试邀请返回结果
 */
public record BatchInvitationResultDTO(
    int createdCount,
    int resentCount,
    boolean mailEnabled,
    List<InvitationListItemDTO> invitations,
    List<InvalidEmailItem> failed
) {
  public record InvalidEmailItem(String email, String message) {
  }
}
