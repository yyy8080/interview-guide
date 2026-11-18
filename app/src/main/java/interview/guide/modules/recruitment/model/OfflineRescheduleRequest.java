package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record OfflineRescheduleRequest(
    @NotBlank(message = "请说明改期原因或可参加的时间") @Size(max = 500) String note
) {
}
