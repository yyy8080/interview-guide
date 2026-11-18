package interview.guide.modules.recruitment.service;

import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.AiReviewRecord;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateAssessmentFeedbackDTO;
import interview.guide.modules.recruitment.model.CandidateFailedInterviewQuestionDTO;
import interview.guide.modules.recruitment.model.CandidateFailedWrittenQuestionDTO;
import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionResult;
import interview.guide.modules.recruitment.model.WrittenTestEntity;
import interview.guide.modules.recruitment.model.WrittenTestStatus;
import interview.guide.modules.recruitment.model.WrittenTestSummary;
import interview.guide.modules.recruitment.repository.WrittenTestRepository;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluatedAnswer;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluatedInterview;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 构建候选人提交答题后、终态未通过（AI_REJECTED / HR_REJECTED）时的未通过原因与错题 / 失分题解析。
 * <p>
 * 安全边界：
 * 1. 仅在 AI_REJECTED / HR_REJECTED 状态返回，作答中或审核中绝不返回任何标准答案与解析；
 * 2. 绝不向候选人暴露 HR 内部审核评语（hrComment）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CandidateAssessmentFeedbackService {

  private static final int DEFAULT_PASS_THRESHOLD = 60;
  private static final int MAX_REASONS = 6;

  private final WrittenTestRepository writtenTestRepository;
  private final WrittenTestCodec writtenTestCodec;
  private final InterviewResultReader interviewResultReader;
  private final ObjectMapper objectMapper;

  public CandidateAssessmentFeedbackDTO buildIfRejected(ApplicationEntity app, JobPositionEntity job) {
    if (app == null || (app.getStatus() != ApplicationStatus.AI_REJECTED
        && app.getStatus() != ApplicationStatus.HR_REJECTED)) {
      return null;
    }

    AiReviewRecord aiReview = parseAiReview(app.getAiResultJson());
    int passThreshold = resolvePassThreshold(aiReview, job);
    WrittenTestEntity writtenTest = writtenTestRepository.findByApplicationId(app.getId()).orElse(null);
    List<CandidateFailedWrittenQuestionDTO> failedWritten = extractFailedWrittenQuestions(writtenTest);
    EvaluatedInterview evaluatedInterview = interviewResultReader.readEvaluatedDetail(app);
    List<CandidateFailedInterviewQuestionDTO> failedInterview =
        extractFailedInterviewQuestions(evaluatedInterview, passThreshold, failedWritten.isEmpty());

    String rejectStageLabel = app.getStatus() == ApplicationStatus.AI_REJECTED
        ? "笔试 / 面试评估未通过"
        : "综合复核未通过";
    String scoreSummary = buildScoreSummary(app, aiReview, writtenTest, evaluatedInterview, passThreshold);
    String summary = buildSummary(app, aiReview, evaluatedInterview, passThreshold);
    List<String> reasons = buildReasons(aiReview, evaluatedInterview, failedWritten, failedInterview);

    return new CandidateAssessmentFeedbackDTO(
        rejectStageLabel,
        summary,
        scoreSummary,
        reasons,
        failedWritten,
        failedInterview);
  }

  private List<CandidateFailedWrittenQuestionDTO> extractFailedWrittenQuestions(WrittenTestEntity test) {
    if (test == null || (test.getStatus() != WrittenTestStatus.GRADED
        && test.getStatus() != WrittenTestStatus.SUBMITTED)) {
      return List.of();
    }
    try {
      List<WrittenQuestion> questions = writtenTestCodec.readQuestions(test.getQuestionsJson());
      if (questions.isEmpty()) {
        return List.of();
      }
      Map<Integer, List<String>> answers = writtenTestCodec.readAnswers(test.getAnswersJson());
      Map<Integer, WrittenQuestionResult> results = writtenTestCodec.readResults(test.getResultsJson()).stream()
          .collect(Collectors.toMap(WrittenQuestionResult::index, Function.identity(), (a, b) -> b));
      // 尚未完成判分（results 为空且 status 为 SUBMITTED）时不提前暴露题目解析
      if (test.getStatus() == WrittenTestStatus.SUBMITTED && results.isEmpty()) {
        return List.of();
      }
      List<CandidateFailedWrittenQuestionDTO> failed = new ArrayList<>();
      for (WrittenQuestion q : questions) {
        WrittenQuestionResult r = results.get(q.index());
        if (r != null && Boolean.TRUE.equals(r.correct())) {
          continue;
        }
        List<String> candidateAnswer = r != null && r.answer() != null
            ? r.answer()
            : answers.getOrDefault(q.index(), List.of());
        failed.add(new CandidateFailedWrittenQuestionDTO(
            q.index(),
            q.type(),
            q.question(),
            q.options() == null ? List.of() : q.options(),
            blankToNull(q.category()),
            candidateAnswer,
            q.answers() == null ? List.of() : q.answers(),
            blankToNull(q.explanation()),
            r != null ? blankToNull(r.reason()) : null));
      }
      return List.copyOf(failed);
    } catch (Exception e) {
      log.warn("构建笔试未通过题目解析失败: testId={}, error={}", test.getId(), e.getMessage());
      return List.of();
    }
  }

  private List<CandidateFailedInterviewQuestionDTO> extractFailedInterviewQuestions(
      EvaluatedInterview evaluated, int passThreshold, boolean noFailedWritten) {
    if (evaluated == null || evaluated.answers() == null || evaluated.answers().isEmpty()) {
      return List.of();
    }
    List<EvaluatedAnswer> belowPass = evaluated.answers().stream()
        .filter(a -> a.score() == null || a.score() < passThreshold)
        .toList();

    List<EvaluatedAnswer> selected = belowPass;
    // 若所有单题刚过及格线但整体因横向对比或高置信度薄弱项未通过，补充得分最低的薄弱题目（< 80 分，最多 3 题）供复盘
    if (selected.isEmpty() && noFailedWritten) {
      selected = evaluated.answers().stream()
          .filter(a -> a.score() != null && a.score() < 80)
          .sorted(Comparator.comparingInt(EvaluatedAnswer::score))
          .limit(3)
          .toList();
    }

    return selected.stream()
        .map(a -> new CandidateFailedInterviewQuestionDTO(
            a.questionIndex(),
            blankToNull(a.category()),
            a.question(),
            a.userAnswer() == null || a.userAnswer().isBlank() ? "（未作答）" : a.userAnswer().trim(),
            a.score(),
            passThreshold,
            blankToNull(a.feedback()),
            blankToNull(a.referenceAnswer()),
            a.keyPoints() == null ? List.of() : a.keyPoints().stream()
                .filter(k -> k != null && !k.isBlank())
                .map(String::trim)
                .toList()))
        .toList();
  }

  private static String buildScoreSummary(ApplicationEntity app, AiReviewRecord aiReview,
                                          WrittenTestEntity writtenTest, EvaluatedInterview evaluated,
                                          int passThreshold) {
    if (aiReview != null && aiReview.written() != null) {
      WrittenTestSummary w = aiReview.written();
      if (app.getStatus() == ApplicationStatus.AI_REJECTED) {
        return "综合得分 " + w.finalScore() + " 分（在线笔试 " + w.writtenScore() + " 分，答对 "
            + w.correctCount() + "/" + w.totalCount() + " 题；在线面试 " + w.interviewScore()
            + " 分；岗位通过线 " + passThreshold + " 分）";
      }
      return "在线笔试 " + w.writtenScore() + " 分（答对 " + w.correctCount() + "/" + w.totalCount()
          + " 题） · 在线面试 " + w.interviewScore() + " 分 · 综合得分 " + w.finalScore() + " 分";
    }
    Integer interviewScore = aiReview != null && aiReview.interviewScore() != null
        ? aiReview.interviewScore()
        : (evaluated != null ? evaluated.overallScore() : app.getAiScore());
    if (interviewScore != null) {
      if (app.getStatus() == ApplicationStatus.AI_REJECTED) {
        return "面试评估得分 " + interviewScore + " 分（岗位通过线 " + passThreshold + " 分）";
      }
      return "在线面试评估得分 " + interviewScore + " 分";
    }
    if (writtenTest != null && writtenTest.getScore() != null) {
      return "在线笔试得分 " + writtenTest.getScore() + " 分（答对 "
          + (writtenTest.getCorrectCount() == null ? 0 : writtenTest.getCorrectCount()) + "/"
          + writtenTest.getTotalCount() + " 题）";
    }
    return null;
  }

  private static String buildSummary(ApplicationEntity app, AiReviewRecord aiReview,
                                     EvaluatedInterview evaluated, int passThreshold) {
    if (app.getStatus() == ApplicationStatus.AI_REJECTED) {
      if (aiReview != null && aiReview.llm() != null && aiReview.llm().summary() != null
          && !aiReview.llm().summary().isBlank()) {
        return aiReview.llm().summary().trim();
      }
      if (evaluated != null && evaluated.overallFeedback() != null && !evaluated.overallFeedback().isBlank()) {
        return evaluated.overallFeedback().trim();
      }
      Integer eff = aiReview != null ? aiReview.effectiveScore() : app.getAiScore();
      if (eff != null && eff < passThreshold) {
        return "您在本次在线答题中的综合评估得分未达到当前岗位的通过标准，暂未能进入下一环节。";
      }
      return "经综合评估您的笔试与面试作答表现，与当前岗位核心要求尚存差距，暂未能进入下一环节。";
    }

    // HR_REJECTED：绝不暴露 hrComment 内部评语，给出客观友好的复盘引导
    if (evaluated != null && evaluated.overallFeedback() != null && !evaluated.overallFeedback().isBlank()) {
      return "经综合复核您的岗位匹配度与在线答题表现，本次暂未能进入下一环节。整体评估参考："
          + evaluated.overallFeedback().trim();
    }
    return "经综合复核您的岗位匹配度与在线答题表现，本次暂未能进入下一环节。您可结合下方未通过题目解析进行针对性复盘。";
  }

  private static List<String> buildReasons(AiReviewRecord aiReview,
                                           EvaluatedInterview evaluated,
                                           List<CandidateFailedWrittenQuestionDTO> failedWritten,
                                           List<CandidateFailedInterviewQuestionDTO> failedInterview) {
    Set<String> items = new LinkedHashSet<>();
    if (aiReview != null && aiReview.llm() != null) {
      if ("REJECT".equalsIgnoreCase(aiReview.llm().decision()) && aiReview.llm().reasons() != null) {
        aiReview.llm().reasons().forEach(r -> addCleaned(items, r));
      }
      if (aiReview.llm().risks() != null) {
        aiReview.llm().risks().forEach(r -> addCleaned(items, r));
      }
    }
    if (evaluated != null && evaluated.improvements() != null) {
      evaluated.improvements().forEach(imp -> addCleaned(items, imp));
    }
    if (items.isEmpty() && !failedWritten.isEmpty()) {
      items.add("在线笔试共有 " + failedWritten.size() + " 道题目未能答对，建议结合下方错题标准答案与解析查漏补缺。");
    }
    if (items.isEmpty() && !failedInterview.isEmpty()) {
      items.add("在线面试共有 " + failedInterview.size() + " 道题目得分存在提升空间，建议参考下方参考答案梳理核心原理与工程实践。");
    }
    return items.stream().limit(MAX_REASONS).toList();
  }

  private static void addCleaned(Set<String> target, String text) {
    if (text == null) {
      return;
    }
    String trimmed = text.trim();
    if (!trimmed.isEmpty() && target.size() < MAX_REASONS) {
      target.add(trimmed);
    }
  }

  private static int resolvePassThreshold(AiReviewRecord aiReview, JobPositionEntity job) {
    if (aiReview != null && aiReview.passThreshold() != null) {
      return aiReview.passThreshold();
    }
    if (job != null && job.getAiPassThreshold() != null) {
      return job.getAiPassThreshold();
    }
    return DEFAULT_PASS_THRESHOLD;
  }

  private AiReviewRecord parseAiReview(String json) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, AiReviewRecord.class);
    } catch (Exception e) {
      log.warn("解析 aiResultJson 失败: error={}", e.getMessage());
      return null;
    }
  }

  private static String blankToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
