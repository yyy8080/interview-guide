package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 开始在线笔试请求（候选人可在开考前确认或调整自选定位、选填 AI 相关经验与个人特长）
 */
public record StartWrittenTestRequest(
    @Pattern(regexp = "junior|mid|senior", message = "应聘定位只能是 junior / mid / senior")
    String candidateLevel,
    @Pattern(regexp = "|NONE|TOOL_USER|PROJECT_PRACTICE", message = "AI 相关经验选项不合法")
    String aiExperience,
    @Size(max = 1000, message = "其他特长与补充说明不能超过 1000 字")
    String specialties
) {
  public StartWrittenTestRequest(String candidateLevel) {
    this(candidateLevel, null, null);
  }
}
