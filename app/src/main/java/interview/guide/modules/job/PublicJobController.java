package interview.guide.modules.job;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.Result;
import interview.guide.modules.job.model.PublicJobDTO;
import interview.guide.modules.job.model.PublicJobDetailDTO;
import interview.guide.modules.job.service.JobPositionService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候选人门户：公开岗位接口（无需登录）
 */
@RestController
@RequestMapping("/api/public/jobs")
@RequiredArgsConstructor
public class PublicJobController {

  private final JobPositionService jobPositionService;

  @GetMapping
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 30)
  public Result<List<PublicJobDTO>> list() {
    return Result.success(jobPositionService.listPublic());
  }

  @GetMapping("/{id}")
  @RateLimit(dimension = RateLimit.Dimension.IP, count = 30)
  public Result<PublicJobDetailDTO> get(@PathVariable Long id) {
    return Result.success(jobPositionService.getPublic(id));
  }
}
