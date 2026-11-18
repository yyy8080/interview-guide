package interview.guide.modules.job.model;

import jakarta.validation.constraints.NotNull;

/**
 * 岗位状态变更请求
 */
public record UpdateJobStatusRequest(
    @NotNull(message = "目标状态不能为空")
    JobStatus status
) {}
