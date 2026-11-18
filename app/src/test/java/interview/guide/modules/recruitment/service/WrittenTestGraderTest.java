package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.recruitment.model.FillBlankJudgement;
import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionResult;
import interview.guide.modules.recruitment.model.WrittenQuestionType;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.DefaultResourceLoader;

@ExtendWith(MockitoExtension.class)
@DisplayName("在线笔试自动判分测试")
class WrittenTestGraderTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;

  @Mock
  private ChatClient chatClient;

  private WrittenTestGrader grader;

  @BeforeEach
  void setUp() throws IOException {
    grader = new WrittenTestGrader(llmProviderRegistry, structuredOutputInvoker, new DefaultResourceLoader());
  }

  @Test
  @DisplayName("单选/多选精确集合判分（少选不得分），填空题规范化命中时不调用 LLM")
  void gradesChoicesAndExactFillWithoutCallingLlm() {
    List<WrittenQuestion> questions = List.of(
        new WrittenQuestion(0, WrittenQuestionType.SINGLE_CHOICE, "Q0",
            List.of("O1", "O2", "O3"), List.of("B"), "", "Java"),
        new WrittenQuestion(1, WrittenQuestionType.MULTIPLE_CHOICE, "Q1",
            List.of("O1", "O2", "O3", "O4"), List.of("A", "C"), "", "Java"),
        new WrittenQuestion(2, WrittenQuestionType.MULTIPLE_CHOICE, "Q2",
            List.of("O1", "O2", "O3", "O4"), List.of("A", "B", "D"), "", "Java"),
        new WrittenQuestion(3, WrittenQuestionType.FILL_BLANK, "默认端口：____",
            List.of(), List.of("6379", "TCP 6379"), "", "Redis")
    );

    Map<Integer, List<String>> answers = Map.of(
        0, List.of("b"),                // 单选正确
        1, List.of("c", "a"),           // 多选乱序全对 → 正确
        2, List.of("A", "B"),           // 多选少选 D → 错误
        3, List.of("  “ｔｃｐ 6379。” ") // 全角 + 引号 + 句号 + 空白 → 规则匹配正确
    );

    WrittenTestGrader.GradeResult result = grader.grade(questions, answers, null);

    assertThat(result.correctCount()).isEqualTo(3);
    assertThat(result.score()).isEqualTo(75);
    assertThat(result.results().get(0).correct()).isTrue();
    assertThat(result.results().get(1).correct()).isTrue();
    assertThat(result.results().get(2).correct()).isFalse();
    assertThat(result.results().get(3).correct()).isTrue();
    assertThat(result.results().get(3).judgedBy()).isEqualTo(WrittenQuestionResult.RULE);
    verify(structuredOutputInvoker, never()).invoke(any(), any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  @DisplayName("填空题规则未命中时批量调用 AI 语义判分；AI 异常时降级为 AI_FALLBACK 不阻塞交卷")
  void delegatesNonExactFillToAiAndFallsBackOnError() {
    List<WrittenQuestion> questions = List.of(
        new WrittenQuestion(0, WrittenQuestionType.FILL_BLANK, "InnoDB 默认隔离级别：____",
            List.of(), List.of("REPEATABLE READ"), "", "MySQL"),
        new WrittenQuestion(1, WrittenQuestionType.FILL_BLANK, "HTTP 200 含义：____",
            List.of(), List.of("OK"), "", "HTTP")
    );
    Map<Integer, List<String>> answers = Map.of(
        0, List.of("可重复读"),
        1, List.of("重定向")
    );

    when(llmProviderRegistry.getChatClientOrDefault(any())).thenReturn(chatClient);
    when(structuredOutputInvoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new FillBlankJudgement(List.of(
            new FillBlankJudgement.Item(0, true, "可重复读与 REPEATABLE READ 同义"),
            new FillBlankJudgement.Item(1, false, "重定向为 3xx 状态码")
        )));

    WrittenTestGrader.GradeResult aiResult = grader.grade(questions, answers, "openai");
    assertThat(aiResult.correctCount()).isEqualTo(1);
    assertThat(aiResult.score()).isEqualTo(50);
    assertThat(aiResult.results().get(0).judgedBy()).isEqualTo(WrittenQuestionResult.AI);
    assertThat(aiResult.results().get(0).correct()).isTrue();
    assertThat(aiResult.results().get(1).correct()).isFalse();

    // AI 调用失败降级
    when(structuredOutputInvoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new BusinessException(ErrorCode.WRITTEN_TEST_JUDGE_FAILED, "timeout"));

    WrittenTestGrader.GradeResult fallback = grader.grade(questions, answers, "openai");
    assertThat(fallback.correctCount()).isZero();
    assertThat(fallback.results()).allMatch(r -> WrittenQuestionResult.AI_FALLBACK.equals(r.judgedBy()));
  }
}
