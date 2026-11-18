package interview.guide.modules.job.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * JD 解析预览请求
 */
public record ParseJobJdRequest(
    @NotBlank(message = "职位描述不能为空")
    @Size(max = 20000, message = "职位描述不能超过20000字")
    String jdText
) {}
