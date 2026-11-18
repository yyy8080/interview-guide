package interview.guide.modules.recruitment.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 文字面试提交答案
 *
 * @param telemetry 本题作答遥测（前端无感采集，可为空）
 */
public record CandidateAnswerRequest(
    @NotNull(message = "题目序号不能为空") @Min(0) Integer questionIndex,
    @NotNull(message = "答案不能为空") @Size(max = 10000, message = "答案不能超过 10000 字") String answer,
    @Valid AnswerTelemetry telemetry
) {

  public CandidateAnswerRequest(Integer questionIndex, String answer) {
    this(questionIndex, answer, null);
  }
}
