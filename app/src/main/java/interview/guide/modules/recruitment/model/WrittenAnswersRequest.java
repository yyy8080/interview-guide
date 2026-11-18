package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;

/**
 * 保存 / 提交笔试作答：题号 -> 选项字母（选择题）或填空内容（填空题，取第一项）
 */
public record WrittenAnswersRequest(
    @NotNull(message = "作答内容不能为空")
    @Size(max = 50, message = "题目数量超出范围")
    Map<Integer, @Size(max = 10, message = "选项数量超出范围") List<@Size(max = 500, message = "单题作答不能超过500字") String>> answers
) {
}
