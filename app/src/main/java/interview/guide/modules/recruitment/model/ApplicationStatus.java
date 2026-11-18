package interview.guide.modules.recruitment.model;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 投递单状态机。
 * <pre>
 * SUBMITTED ─┬─(开启初筛)→ SCREENING ─┬→ INTERVIEW_INVITED
 *            │                        └→ SCREEN_REJECTED（终态）
 *            └─(关闭初筛)→ INTERVIEW_INVITED ─┬→ INTERVIEWING → AI_REVIEWING ─┬→ AI_PASSED → HR_APPROVED / HR_REJECTED
 *                                             └→ EXPIRED                      └→ AI_REJECTED（终态）
 * HR_APPROVED → OFFLINE_SCHEDULED ⇄（改期）→ OFFLINE_CONFIRMED
 * </pre>
 * 所有状态变更必须经过 {@code ApplicationStateMachine}，校验迁移并写审计日志。
 */
public enum ApplicationStatus {
  SUBMITTED,
  SCREENING,
  SCREEN_REJECTED,
  INTERVIEW_INVITED,
  INTERVIEWING,
  AI_REVIEWING,
  AI_PASSED,
  AI_REJECTED,
  HR_APPROVED,
  HR_REJECTED,
  OFFLINE_SCHEDULED,
  OFFLINE_CONFIRMED,
  EXPIRED;

  private static final Map<ApplicationStatus, Set<ApplicationStatus>> TRANSITIONS = Map.ofEntries(
      Map.entry(SUBMITTED, EnumSet.of(SCREENING, INTERVIEW_INVITED, HR_REJECTED)),
      Map.entry(SCREENING, EnumSet.of(INTERVIEW_INVITED, SCREEN_REJECTED, HR_REJECTED)),
      Map.entry(SCREEN_REJECTED, EnumSet.noneOf(ApplicationStatus.class)),
      Map.entry(INTERVIEW_INVITED, EnumSet.of(INTERVIEWING, EXPIRED, HR_REJECTED)),
      Map.entry(INTERVIEWING, EnumSet.of(AI_REVIEWING, HR_REJECTED)),
      Map.entry(AI_REVIEWING, EnumSet.of(AI_PASSED, AI_REJECTED)),
      Map.entry(AI_PASSED, EnumSet.of(HR_APPROVED, HR_REJECTED)),
      Map.entry(AI_REJECTED, EnumSet.noneOf(ApplicationStatus.class)),
      Map.entry(HR_APPROVED, EnumSet.of(OFFLINE_SCHEDULED, HR_REJECTED)),
      Map.entry(HR_REJECTED, EnumSet.noneOf(ApplicationStatus.class)),
      Map.entry(OFFLINE_SCHEDULED, EnumSet.of(OFFLINE_SCHEDULED, OFFLINE_CONFIRMED, HR_REJECTED)),
      Map.entry(OFFLINE_CONFIRMED, EnumSet.of(OFFLINE_SCHEDULED, HR_REJECTED)),
      Map.entry(EXPIRED, EnumSet.of(INTERVIEW_INVITED))
  );

  public boolean canTransitTo(ApplicationStatus target) {
    return TRANSITIONS.getOrDefault(this, Set.of()).contains(target);
  }

  /** 是否为不通过终态 */
  public boolean isRejected() {
    return this == SCREEN_REJECTED || this == AI_REJECTED || this == HR_REJECTED;
  }

  /** 候选人可见阶段 */
  public CandidateStage toCandidateStage() {
    return switch (this) {
      case SUBMITTED, SCREENING -> CandidateStage.SCREENING;
      case INTERVIEW_INVITED -> CandidateStage.INTERVIEW_PENDING;
      case INTERVIEWING -> CandidateStage.INTERVIEWING;
      case AI_REVIEWING, AI_PASSED -> CandidateStage.UNDER_REVIEW;
      case HR_APPROVED -> CandidateStage.PASSED;
      case OFFLINE_SCHEDULED -> CandidateStage.OFFLINE_INVITED;
      case OFFLINE_CONFIRMED -> CandidateStage.OFFLINE_CONFIRMED;
      case SCREEN_REJECTED, AI_REJECTED, HR_REJECTED -> CandidateStage.REJECTED;
      case EXPIRED -> CandidateStage.EXPIRED;
    };
  }
}
