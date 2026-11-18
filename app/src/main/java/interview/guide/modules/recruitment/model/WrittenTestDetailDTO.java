package interview.guide.modules.recruitment.model;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台笔试详情
 *
 * @param weight 笔试成绩在综合分中的权重（%）
 */
public record WrittenTestDetailDTO(
    WrittenTestStatus status,
    Integer score,
    Integer correctCount,
    int totalCount,
    int weight,
    LocalDateTime startedAt,
    LocalDateTime deadlineAt,
    LocalDateTime submittedAt,
    LocalDateTime gradedAt,
    List<WrittenTestItemDTO> items
) {
}
