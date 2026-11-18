package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.Size;

public record HrReviewRequest(@Size(max = 2000, message = "审核意见不能超过 2000 字") String comment) {
}
