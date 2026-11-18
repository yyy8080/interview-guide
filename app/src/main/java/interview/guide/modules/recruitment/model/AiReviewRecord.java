package interview.guide.modules.recruitment.model;

import java.util.Map;

/**
 * 写入 ai_result_json 的完整审核记录
 *
 * @param finalDecision  最终结论
 * @param interviewScore 面试评估总分（0-100）
 * @param passThreshold  岗位通过分数线
 * @param llm            LLM 综合判断（失败时为空）
 * @param fallback       是否走了兜底规则（LLM 失败 / 评估失败）
 * @param note           说明
 */
public record AiReviewRecord(
    AiDecision finalDecision,
    Integer interviewScore,
    Integer passThreshold,
    DecisionResult llm,
    boolean fallback,
    String note,
    Map<String, Long> proctorCounts
) {
}
