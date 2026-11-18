package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record StartInterviewRequest(
    @NotNull(message = "请选择面试形式") InterviewMode mode,
    @Pattern(regexp = "junior|mid|senior", message = "应聘定位只能是 junior / mid / senior") String candidateLevel
) {
  public StartInterviewRequest(InterviewMode mode) {
    this(mode, null);
  }
}
