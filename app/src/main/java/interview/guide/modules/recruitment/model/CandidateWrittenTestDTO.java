package interview.guide.modules.recruitment.model;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 候选人笔试状态
 *
 * @param status           状态；IN_PROGRESS 时返回题目与已保存的作答
 * @param questions        题目（交卷后为空列表）
 * @param answers          已保存的作答
 * @param remainingSeconds 剩余作答时间
 */
public record CandidateWrittenTestDTO(
    Long applicationId,
    String candidateLevel,
    String candidateTrack,
    String aiExperience,
    String specialties,
    WrittenTestStatus status,
    int totalQuestions,
    List<CandidateWrittenQuestionDTO> questions,
    Map<Integer, List<String>> answers,
    LocalDateTime startedAt,
    LocalDateTime deadlineAt,
    Long remainingSeconds,
    String watermarkText,
    boolean proctorAwayReminder
) {
}
