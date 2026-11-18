package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

/**
 * HR 安排 / 修改线下面试
 */
public record OfflineInterviewRequest(
    @NotNull(message = "面试时间不能为空") @Future(message = "面试时间必须晚于当前时间") LocalDateTime interviewTime,
    @NotNull(message = "面试时长不能为空") @Min(10) @Max(480) Integer durationMinutes,
    @NotBlank(message = "面试地点不能为空") @Size(max = 512) String location,
    @Size(max = 255) String interviewer,
    @Size(max = 2000) String notes,
    @Min(1) @Max(20) Integer roundNumber
) {
}
