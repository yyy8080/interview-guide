package interview.guide.modules.job;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.common.security.CurrentAdmin;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.job.model.JobPositionDTO;
import interview.guide.modules.job.model.JobPositionRequest;
import interview.guide.modules.job.model.JobStatus;
import interview.guide.modules.job.model.ParseJobJdRequest;
import interview.guide.modules.job.model.UpdateJobStatusRequest;
import interview.guide.modules.job.service.JobPositionService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台岗位管理接口
 */
@RestController
@RequestMapping("/api/admin/jobs")
@RequiredArgsConstructor
public class AdminJobController {

  private final JobPositionService jobPositionService;

  @GetMapping
  public Result<List<JobPositionDTO>> list(
      @RequestParam(required = false) JobStatus status,
      @RequestParam(required = false) String keyword) {
    return Result.success(jobPositionService.list(status, keyword));
  }

  @GetMapping("/{id}")
  public Result<JobPositionDTO> get(@PathVariable Long id) {
    return Result.success(jobPositionService.get(id));
  }

  @PostMapping
  public Result<JobPositionDTO> create(@Valid @RequestBody JobPositionRequest request) {
    return Result.success(jobPositionService.create(request, CurrentAdmin.id()));
  }

  @PutMapping("/{id}")
  public Result<JobPositionDTO> update(@PathVariable Long id, @Valid @RequestBody JobPositionRequest request) {
    return Result.success(jobPositionService.update(id, request));
  }

  @PutMapping("/{id}/status")
  public Result<JobPositionDTO> changeStatus(@PathVariable Long id,
                                             @Valid @RequestBody UpdateJobStatusRequest request) {
    return Result.success(jobPositionService.changeStatus(id, request.status()));
  }

  @PostMapping("/{id}/copy")
  public Result<JobPositionDTO> copy(@PathVariable Long id) {
    return Result.success(jobPositionService.copy(id, CurrentAdmin.id()));
  }

  @DeleteMapping("/{id}")
  public Result<Void> delete(@PathVariable Long id) {
    jobPositionService.delete(id);
    return Result.success();
  }

  @PostMapping("/parse-jd")
  @RateLimit(dimension = RateLimit.Dimension.USER, count = 5)
  public Result<List<CategoryDTO>> parseJd(@Valid @RequestBody ParseJobJdRequest request) {
    return Result.success(jobPositionService.parseJd(request.jdText()));
  }
}
