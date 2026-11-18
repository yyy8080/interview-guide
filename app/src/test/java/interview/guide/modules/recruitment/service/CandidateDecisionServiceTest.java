package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.StructuredOutputInvoker;
import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.AiDecision;
import interview.guide.modules.recruitment.model.AiReviewRecord;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.DecisionResult;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluationSnapshot;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluationState;
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
@DisplayName("AI 综合决策服务测试")
class CandidateDecisionServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;

  @Mock
  private StructuredOutputInvoker structuredOutputInvoker;

  @Mock
  private ChatClient chatClient;

  private CandidateDecisionService service;

  @BeforeEach
  void setUp() throws IOException {
    RecruitmentProperties properties = new RecruitmentProperties();
    properties.setRejectConfidenceThreshold(0.8);
    service = new CandidateDecisionService(
        llmProviderRegistry, structuredOutputInvoker, properties, new DefaultResourceLoader());
  }

  @Test
  @DisplayName("分数低于通过线直接判定 REJECT；分数达标但 LLM 高置信度判定 REJECT 同样淘汰")
  void combinesScoreThresholdAndLlmConfidence() {
    DecisionResult highConfReject = new DecisionResult("REJECT", 0.85, "核心经验造假", List.of(), List.of(), List.of());
    DecisionResult lowConfReject = new DecisionResult("REJECT", 0.65, "略有不足", List.of(), List.of(), List.of());
    DecisionResult passResult = new DecisionResult("PASS", 0.90, "表现扎实", List.of(), List.of(), List.of());

    assertThat(CandidateDecisionService.combine(59, 60, passResult, 0.8)).isEqualTo(AiDecision.REJECT);
    assertThat(CandidateDecisionService.combine(78, 60, highConfReject, 0.8)).isEqualTo(AiDecision.REJECT);
    assertThat(CandidateDecisionService.combine(78, 60, lowConfReject, 0.8)).isEqualTo(AiDecision.PASS);
    assertThat(CandidateDecisionService.combine(78, 60, passResult, 0.8)).isEqualTo(AiDecision.PASS);
    assertThat(CandidateDecisionService.combine(60, 60, null, 0.8)).isEqualTo(AiDecision.PASS);
  }

  @Test
  @DisplayName("LLM 调用异常时自动降级为仅按分数线规则判定，不阻塞投递流转")
  void fallsBackToScoreRuleWhenLlmFails() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(42L);
    JobPositionEntity job = new JobPositionEntity();
    job.setTitle("后端工程师");
    job.setJdText("熟悉 Java 与分布式系统");
    job.setAiPassThreshold(70);

    when(llmProviderRegistry.getChatClientOrDefault(any())).thenReturn(chatClient);
    when(structuredOutputInvoker.invoke(any(), any(), any(), any(), any(), any(), any(), any()))
        .thenThrow(new BusinessException(ErrorCode.DECISION_FAILED, "超时"));

    AiReviewRecord passFallback = service.decide(
        app, job, null, new EvaluationSnapshot(EvaluationState.COMPLETED, 75, "回答清晰"), Map.of(), false);
    assertThat(passFallback.finalDecision()).isEqualTo(AiDecision.PASS);
    assertThat(passFallback.fallback()).isTrue();

    AiReviewRecord rejectFallback = service.decide(
        app, job, null, new EvaluationSnapshot(EvaluationState.COMPLETED, 58, "基础薄弱"), Map.of(), false);
    assertThat(rejectFallback.finalDecision()).isEqualTo(AiDecision.REJECT);
    assertThat(rejectFallback.fallback()).isTrue();
  }

  @Test
  @DisplayName("缺少面试评估分数时不误判淘汰，标记 fallback 供人工复核")
  void returnsNullDecisionWhenScoreMissing() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(43L);
    JobPositionEntity job = new JobPositionEntity();
    job.setAiPassThreshold(65);

    AiReviewRecord record = service.decide(
        app, job, null, new EvaluationSnapshot(EvaluationState.FAILED, null, null), Map.of("PASTE", 3L), true);

    assertThat(record.finalDecision()).isNull();
    assertThat(record.fallback()).isTrue();
    assertThat(record.proctorCounts()).containsEntry("PASTE", 3L);
  }
}
