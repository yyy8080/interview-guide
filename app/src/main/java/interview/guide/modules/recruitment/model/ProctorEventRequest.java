package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ProctorEventRequest(
    @NotNull(message = "事件类型不能为空") ProctorEventType type,
    @Size(max = 255) String detail
) {
}
