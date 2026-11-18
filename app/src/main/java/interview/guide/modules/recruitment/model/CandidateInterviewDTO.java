package interview.guide.modules.recruitment.model;

import interview.guide.modules.job.model.InterviewMode;
import java.time.LocalDateTime;

/**
 * 候选人在线面试状态
 *
 * @param voiceWsPath 语音面试 WebSocket 路径（相对 API 入口），文字面试为空
 * @param proctorAwayReminder 长时间离开页面回来后是否给出柔和提醒
 * @param watermarkText 页面水印文字，岗位关闭水印时为空
 */
public record CandidateInterviewDTO(
    Long applicationId,
    String jobTitle,
    InterviewMode mode,
    CandidateStage stage,
    LocalDateTime startedAt,
    LocalDateTime deadlineAt,
    Long remainingSeconds,
    int totalQuestions,
    int currentQuestionIndex,
    CandidateQuestionDTO currentQuestion,
    boolean finished,
    Long voiceSessionId,
    String voiceWsPath,
    Integer plannedDurationMinutes,
    boolean proctorAwayReminder,
    String watermarkText,
    boolean writtenTestEnabled,
    WrittenTestStatus writtenTestStatus,
    int writtenQuestionCount,
    int writtenTimeLimitMinutes
) {
}
