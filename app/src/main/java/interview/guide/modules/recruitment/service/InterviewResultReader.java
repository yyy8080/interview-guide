package interview.guide.modules.recruitment.service;

import interview.guide.common.model.AsyncTaskStatus;
import interview.guide.modules.interview.model.InterviewDetailDTO;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.service.InterviewHistoryService;
import interview.guide.modules.interview.service.InterviewPersistenceService;
import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.voiceinterview.dto.VoiceEvaluationDetailDTO;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import interview.guide.modules.voiceinterview.service.VoiceInterviewEvaluationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 读取投递单关联的在线面试评估状态与结果（文字 / 语音统一视图）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InterviewResultReader {

  private static final int MAX_ANSWER_CHARS = 600;
  private static final int MAX_TOTAL_CHARS = 12_000;

  private final InterviewPersistenceService interviewPersistenceService;
  private final InterviewHistoryService interviewHistoryService;
  private final VoiceInterviewSessionRepository voiceSessionRepository;
  private final VoiceInterviewEvaluationService voiceEvaluationService;

  public enum EvaluationState {
    /** 尚未结束 / 评估中 */
    PENDING,
    COMPLETED,
    FAILED,
    /** 会话不存在 */
    MISSING
  }

  public record EvaluationSnapshot(EvaluationState state, Integer score, String evaluationText) {
  }

  public record EvaluatedAnswer(
      int questionIndex,
      String category,
      String question,
      String userAnswer,
      Integer score,
      String feedback,
      String referenceAnswer,
      List<String> keyPoints
  ) {
  }

  public record EvaluatedInterview(
      Integer overallScore,
      String overallFeedback,
      List<String> improvements,
      List<EvaluatedAnswer> answers
  ) {
  }

  /** 仅读取评估状态（不加载评估详情） */
  public EvaluationState state(ApplicationEntity application) {
    if (application.getInterviewMode() == InterviewMode.VOICE) {
      if (application.getVoiceSessionId() == null) {
        return EvaluationState.MISSING;
      }
      return voiceSessionRepository.findById(application.getVoiceSessionId())
          .map(session -> toState(session.getEvaluateStatus()))
          .orElse(EvaluationState.MISSING);
    }
    if (application.getInterviewSessionId() == null) {
      return EvaluationState.MISSING;
    }
    return interviewPersistenceService.findBySessionId(application.getInterviewSessionId())
        .map(session -> toState(session.getEvaluateStatus()))
        .orElse(EvaluationState.MISSING);
  }

  /** 语音会话是否已结束（WebSocket 断开 / 主动结束） */
  public boolean isVoiceSessionEnded(Long voiceSessionId) {
    if (voiceSessionId == null) {
      return false;
    }
    return voiceSessionRepository.findById(voiceSessionId)
        .map(session -> session.getEndTime() != null
            || session.getCurrentPhase() == VoiceInterviewSessionEntity.InterviewPhase.COMPLETED)
        .orElse(false);
  }

  /** 读取结构化评估详情（用于终态未通过后生成未达标题目解析；不存在或未完成返回 null） */
  public EvaluatedInterview readEvaluatedDetail(ApplicationEntity application) {
    if (application == null || state(application) != EvaluationState.COMPLETED) {
      return null;
    }
    try {
      if (application.getInterviewMode() == InterviewMode.VOICE) {
        VoiceEvaluationDetailDTO detail = voiceEvaluationService.getEvaluation(application.getVoiceSessionId());
        if (detail == null) {
          return null;
        }
        List<EvaluatedAnswer> answers = detail.getAnswers() == null ? List.of()
            : detail.getAnswers().stream()
                .map(a -> new EvaluatedAnswer(
                    a.getQuestionIndex(),
                    a.getCategory(),
                    a.getQuestion(),
                    a.getUserAnswer(),
                    a.getScore(),
                    a.getFeedback(),
                    a.getReferenceAnswer(),
                    a.getKeyPoints() == null ? List.of() : a.getKeyPoints()))
                .toList();
        return new EvaluatedInterview(
            detail.getOverallScore(),
            detail.getOverallFeedback(),
            detail.getImprovements() == null ? List.of() : detail.getImprovements(),
            answers);
      }
      InterviewDetailDTO detail = interviewHistoryService.getInterviewDetail(application.getInterviewSessionId());
      if (detail == null) {
        return null;
      }
      List<EvaluatedAnswer> answers = detail.answers() == null ? List.of()
          : detail.answers().stream()
              .map(a -> new EvaluatedAnswer(
                  a.questionIndex() == null ? 0 : a.questionIndex(),
                  a.category(),
                  a.question(),
                  a.userAnswer(),
                  a.score(),
                  a.feedback(),
                  a.referenceAnswer(),
                  a.keyPoints() == null ? List.of() : a.keyPoints()))
              .toList();
      return new EvaluatedInterview(
          detail.overallScore(),
          detail.overallFeedback(),
          detail.improvements() == null ? List.of() : detail.improvements(),
          answers);
    } catch (Exception e) {
      log.warn("读取结构化面试评估详情失败: applicationId={}, error={}", application.getId(), e.getMessage());
      return null;
    }
  }

  /** 读取评估结果（用于 AI 决策） */
  public EvaluationSnapshot snapshot(ApplicationEntity application) {
    EvaluationState state = state(application);
    if (state != EvaluationState.COMPLETED) {
      return new EvaluationSnapshot(state, null, "");
    }
    try {
      if (application.getInterviewMode() == InterviewMode.VOICE) {
        VoiceEvaluationDetailDTO detail = voiceEvaluationService.getEvaluation(application.getVoiceSessionId());
        StringBuilder sb = new StringBuilder();
        appendOverall(sb, detail.getOverallFeedback(), detail.getStrengths(), detail.getImprovements());
        if (detail.getAnswers() != null) {
          for (VoiceEvaluationDetailDTO.AnswerDetail answer : detail.getAnswers()) {
            appendAnswer(sb, answer.getQuestionIndex(), answer.getCategory(), answer.getQuestion(),
                answer.getUserAnswer(), answer.getScore(), answer.getFeedback());
          }
        }
        return new EvaluationSnapshot(state, detail.getOverallScore(), limit(sb));
      }
      InterviewDetailDTO detail = interviewHistoryService.getInterviewDetail(application.getInterviewSessionId());
      StringBuilder sb = new StringBuilder();
      appendOverall(sb, detail.overallFeedback(), detail.strengths(), detail.improvements());
      if (detail.answers() != null) {
        for (InterviewDetailDTO.AnswerDetailDTO answer : detail.answers()) {
          appendAnswer(sb, answer.questionIndex() == null ? 0 : answer.questionIndex(), answer.category(),
              answer.question(), answer.userAnswer(), answer.score(), answer.feedback());
        }
      }
      return new EvaluationSnapshot(state, detail.overallScore(), limit(sb));
    } catch (Exception e) {
      log.warn("读取面试评估详情失败: applicationId={}, error={}", application.getId(), e.getMessage());
      Integer score = readScoreOnly(application);
      return new EvaluationSnapshot(score == null ? EvaluationState.FAILED : state, score, "");
    }
  }

  private Integer readScoreOnly(ApplicationEntity application) {
    if (application.getInterviewMode() == InterviewMode.VOICE) {
      return null;
    }
    return interviewPersistenceService.findBySessionId(application.getInterviewSessionId())
        .map(InterviewSessionEntity::getOverallScore).orElse(null);
  }

  private static EvaluationState toState(AsyncTaskStatus status) {
    if (status == AsyncTaskStatus.COMPLETED) {
      return EvaluationState.COMPLETED;
    }
    if (status == AsyncTaskStatus.FAILED) {
      return EvaluationState.FAILED;
    }
    return EvaluationState.PENDING;
  }

  private static void appendOverall(StringBuilder sb, String feedback, List<String> strengths,
                                    List<String> improvements) {
    if (feedback != null && !feedback.isBlank()) {
      sb.append("总体评价：").append(feedback.trim()).append('\n');
    }
    if (strengths != null && !strengths.isEmpty()) {
      sb.append("优势：").append(String.join("；", strengths)).append('\n');
    }
    if (improvements != null && !improvements.isEmpty()) {
      sb.append("不足：").append(String.join("；", improvements)).append('\n');
    }
  }

  private static void appendAnswer(StringBuilder sb, int index, String category, String question, String answer,
                                   Integer score, String feedback) {
    sb.append("\n第").append(index + 1).append("题");
    if (category != null && !category.isBlank()) {
      sb.append("【").append(category).append("】");
    }
    sb.append(" 得分：").append(score == null ? "-" : score).append('\n');
    sb.append("问题：").append(cut(question)).append('\n');
    sb.append("回答：").append(answer == null || answer.isBlank() ? "（未作答）" : cut(answer)).append('\n');
    if (feedback != null && !feedback.isBlank()) {
      sb.append("点评：").append(cut(feedback)).append('\n');
    }
  }

  private static String cut(String text) {
    if (text == null) {
      return "";
    }
    String trimmed = text.trim();
    return trimmed.length() > MAX_ANSWER_CHARS ? trimmed.substring(0, MAX_ANSWER_CHARS) + "…" : trimmed;
  }

  private static String limit(StringBuilder sb) {
    return sb.length() > MAX_TOTAL_CHARS ? sb.substring(0, MAX_TOTAL_CHARS) + "\n…（已截断）" : sb.toString();
  }
}
