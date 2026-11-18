package interview.guide.modules.recruitment.model;

public record CandidateAnswerResponse(
    boolean finished,
    CandidateQuestionDTO nextQuestion,
    int currentQuestionIndex,
    int totalQuestions
) {
}
