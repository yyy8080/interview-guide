package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 候选人可见的笔试题目（不含答案与解析）
 */
public record CandidateWrittenQuestionDTO(
    int index,
    WrittenQuestionType type,
    String question,
    List<String> options,
    String category
) {

  public static CandidateWrittenQuestionDTO of(WrittenQuestion question) {
    return new CandidateWrittenQuestionDTO(question.index(), question.type(), question.question(),
        question.options() == null ? List.of() : question.options(), question.category());
  }
}
