package interview.guide.modules.recruitment.controller;

import interview.guide.common.result.PageResult;
import interview.guide.common.result.Result;
import interview.guide.common.security.CurrentAdmin;
import interview.guide.modules.recruitment.model.BatchInvitationResultDTO;
import interview.guide.modules.recruitment.model.BatchResumeImageExtractDTO;
import interview.guide.modules.recruitment.model.CreateInvitationsRequest;
import interview.guide.modules.recruitment.model.InvitationListItemDTO;
import interview.guide.modules.recruitment.model.InvitationParticipationFilter;
import interview.guide.modules.recruitment.model.InvitationStatsDTO;
import interview.guide.modules.recruitment.service.RecruitmentInvitationService;
import interview.guide.modules.recruitment.service.ResumeImageExtractService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 后台定向面试邀请管理接口：发送邮件邀请、批量上传简历图片自动识别姓名与邮箱邀请、查看参面状态、重发与撤销邀请
 */
@RestController
@RequestMapping("/api/admin/invitations")
@RequiredArgsConstructor
public class AdminInvitationController {

  private final RecruitmentInvitationService invitationService;
  private final ResumeImageExtractService resumeImageExtractService;

  @GetMapping
  public Result<PageResult<InvitationListItemDTO>> list(
      @RequestParam(required = false) Long jobId,
      @RequestParam(required = false) InvitationParticipationFilter participation,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.success(invitationService.list(jobId, participation, keyword, page, size));
  }

  @GetMapping("/stats")
  public Result<InvitationStatsDTO> stats(@RequestParam(required = false) Long jobId) {
    return Result.success(invitationService.stats(jobId));
  }

  @PostMapping(value = "/parse-resume-images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Result<BatchResumeImageExtractDTO> parseResumeImages(
      @RequestParam("files") List<MultipartFile> files) {
    return Result.success(resumeImageExtractService.extractBatch(files));
  }

  @PostMapping
  public Result<BatchInvitationResultDTO> send(@Valid @RequestBody CreateInvitationsRequest request) {
    return Result.success(invitationService.sendInvitations(request, CurrentAdmin.id()));
  }

  @PostMapping("/{id}/resend")
  public Result<InvitationListItemDTO> resend(@PathVariable Long id) {
    return Result.success(invitationService.resendInvitation(id, CurrentAdmin.id()));
  }

  @PostMapping("/{id}/revoke")
  public Result<InvitationListItemDTO> revoke(@PathVariable Long id) {
    return Result.success(invitationService.revokeInvitation(id, CurrentAdmin.id()));
  }
}
