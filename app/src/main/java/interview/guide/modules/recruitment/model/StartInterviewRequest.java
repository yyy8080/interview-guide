package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import jakarta.validation.constraints.NotNull;

public record StartInterviewRequest(@NotNull(message = "请选择面试形式") InterviewMode mode) {
}
