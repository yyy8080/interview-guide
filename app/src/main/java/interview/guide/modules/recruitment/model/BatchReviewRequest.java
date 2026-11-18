package interview.guide.modules.recruitment.model;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

public record BatchReviewRequest(
    @NotEmpty(message = "请选择投递记录") @Size(max = 100, message = "单次最多处理 100 条") List<Long> ids,
    boolean approve,
    @Size(max = 2000) String comment
) {
}
