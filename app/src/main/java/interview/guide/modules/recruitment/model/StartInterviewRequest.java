package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StartInterviewRequest(
    @NotNull(message = "请选择面试形式") InterviewMode mode,
    @Pattern(regexp = "junior|mid|senior", message = "应聘定位只能是 junior / mid / senior") String candidateLevel,
    @Pattern(regexp = "|NONE|TOOL_USER|PROJECT_PRACTICE", message = "AI 相关经验选项不合法") String aiExperience,
    @Size(max = 1000, message = "其他特长与补充说明不能超过 1000 字") String specialties
) {
  public StartInterviewRequest(InterviewMode mode) {
    this(mode, null, null, null);
  }

  public StartInterviewRequest(InterviewMode mode, String candidateLevel) {
    this(mode, candidateLevel, null, null);
  }
}
