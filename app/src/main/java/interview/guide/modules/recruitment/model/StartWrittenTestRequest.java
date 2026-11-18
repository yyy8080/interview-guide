package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.Pattern;

/**
 * 开始在线笔试请求（候选人可在开考前确认或调整自选定位）
 */
public record StartWrittenTestRequest(
    @Pattern(regexp = "junior|mid|senior", message = "应聘定位只能是 junior / mid / senior")
    String candidateLevel
) {
}
