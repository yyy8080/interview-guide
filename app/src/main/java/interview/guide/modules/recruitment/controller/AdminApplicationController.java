package interview.guide.modules.recruitment.controller;

import interview.guide.common.result.PageResult;
import interview.guide.common.result.Result;
import interview.guide.common.security.CurrentAdmin;
import interview.guide.modules.recruitment.model.ApplicationDetailDTO;
import interview.guide.modules.recruitment.model.ApplicationListItemDTO;
import interview.guide.modules.recruitment.model.ApplicationStatsDTO;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.BatchReviewRequest;
import interview.guide.modules.recruitment.model.BatchReviewResult;
import interview.guide.modules.recruitment.model.HrReviewRequest;
import interview.guide.modules.recruitment.model.OfflineInterviewRequest;
import interview.guide.modules.recruitment.service.AdminApplicationService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台投递管理接口
 */
@RestController
@RequestMapping("/api/admin/applications")
@RequiredArgsConstructor
public class AdminApplicationController {

  private final AdminApplicationService applicationService;

  @GetMapping
  public Result<PageResult<ApplicationListItemDTO>> list(
      @RequestParam(required = false) Long jobId,
      @RequestParam(required = false) List<ApplicationStatus> statuses,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size) {
    return Result.success(applicationService.list(jobId, statuses, keyword, page, size));
  }

  @GetMapping("/stats")
  public Result<ApplicationStatsDTO> stats(@RequestParam(required = false) Long jobId) {
    return Result.success(applicationService.stats(jobId));
  }

  @GetMapping("/{id}")
  public Result<ApplicationDetailDTO> detail(@PathVariable Long id) {
    return Result.success(applicationService.detail(id));
  }

  @GetMapping("/{id}/resume/file")
  public ResponseEntity<byte[]> resumeFile(@PathVariable Long id) {
    AdminApplicationService.ResumeFile file = applicationService.resumeFile(id);
    MediaType mediaType;
    try {
      mediaType = file.contentType() != null ? MediaType.parseMediaType(file.contentType())
          : MediaType.APPLICATION_OCTET_STREAM;
    } catch (Exception e) {
      mediaType = MediaType.APPLICATION_OCTET_STREAM;
    }
    return ResponseEntity.ok()
        .contentType(mediaType)
        .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
            .filename(file.filename(), StandardCharsets.UTF_8).build().toString())
        .body(file.content());
  }

  @PostMapping("/{id}/approve")
  public Result<ApplicationDetailDTO> approve(@PathVariable Long id,
                                              @Valid @RequestBody(required = false) HrReviewRequest request) {
    return Result.success(applicationService.approve(id, CurrentAdmin.id(), request != null ? request.comment() : null));
  }

  @PostMapping("/{id}/reject")
  public Result<ApplicationDetailDTO> reject(@PathVariable Long id,
                                             @Valid @RequestBody(required = false) HrReviewRequest request) {
    return Result.success(applicationService.reject(id, CurrentAdmin.id(), request != null ? request.comment() : null));
  }

  @PostMapping("/batch-review")
  public Result<BatchReviewResult> batchReview(@Valid @RequestBody BatchReviewRequest request) {
    return Result.success(applicationService.batchReview(request, CurrentAdmin.id()));
  }

  @PostMapping("/{id}/offline-interview")
  public Result<ApplicationDetailDTO> scheduleOffline(@PathVariable Long id,
                                                      @Valid @RequestBody OfflineInterviewRequest request) {
    return Result.success(applicationService.scheduleOffline(id, CurrentAdmin.id(), request));
  }
}
