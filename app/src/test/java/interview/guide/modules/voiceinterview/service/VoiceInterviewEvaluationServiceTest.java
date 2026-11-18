package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.evaluation.EvaluationReport;
import interview.guide.common.evaluation.UnifiedEvaluationService;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.model.VoiceInterviewEvaluationEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewMessageEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试评估服务")
class VoiceInterviewEvaluationServiceTest {

    @Mock
    private UnifiedEvaluationService unifiedEvaluationService;

    @Mock
    private LlmProviderRegistry llmProviderRegistry;

    @Mock
    private VoiceInterviewEvaluationRepository evaluationRepository;

    @Mock
    private VoiceInterviewMessageRepository messageRepository;

    @Mock
    private VoiceInterviewSessionRepository sessionRepository;

    @Mock
    private ResumeRepository resumeRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private InterviewSkillService skillService;

    @InjectMocks
    private VoiceInterviewEvaluationService evaluationService;

    @Test
    @DisplayName("没有有效对话时应显示暂无评分而不是零分")
    void shouldNotAssignZeroScoreWhenNoConversationExists() {
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(1L)
            .roleType("Java 后端开发")
            .startTime(LocalDateTime.now())
            .build();
        when(evaluationRepository.findBySessionId(1L)).thenReturn(Optional.empty());

        evaluationService.saveEmptyEvaluationTransactional(1L, session);

        ArgumentCaptor<VoiceInterviewEvaluationEntity> captor =
            ArgumentCaptor.forClass(VoiceInterviewEvaluationEntity.class);
        verify(evaluationRepository).save(captor.capture());
        assertThat(captor.getValue().getOverallScore()).isNull();
        assertThat(captor.getValue().getOverallFeedback()).contains("暂无可评估内容");
    }

    @Test
    @DisplayName("会话关联简历时将简历原文传入统一评估服务以交叉核验语音作答与简历真实性")
    void passesResumeTextIntoUnifiedEvaluationWhenSessionHasResumeId() {
        VoiceInterviewSessionEntity session = VoiceInterviewSessionEntity.builder()
            .id(2L)
            .resumeId(88L)
            .skillId("java-backend")
            .llmProvider("dashscope")
            .roleType("Java 后端开发")
            .startTime(LocalDateTime.now())
            .build();
        VoiceInterviewMessageEntity qMsg = VoiceInterviewMessageEntity.builder()
            .sessionId(2L)
            .sequenceNum(1)
            .aiGeneratedText("请结合你简历中的订单秒杀项目说明本人负责的防超卖方案？")
            .build();
        VoiceInterviewMessageEntity aMsg = VoiceInterviewMessageEntity.builder()
            .sessionId(2L)
            .sequenceNum(2)
            .userRecognizedText("我使用 Redis Lua 脚本预扣库存并配合异步落库幂等校验。")
            .build();
        ResumeEntity resume = new ResumeEntity();
        resume.setId(88L);
        resume.setResumeText("负责高并发订单秒杀服务，QPS 5000，实现防超卖。");

        ChatClient chatClient = mock(ChatClient.class);
        EvaluationReport report = new EvaluationReport(
            "2", 1, 82, List.of(), List.of(),
            "项目细节回答一致，简历真实性可信。", List.of("Redis 方案清晰"), List.of(), List.of());

        when(sessionRepository.findById(2L)).thenReturn(Optional.of(session));
        when(messageRepository.findBySessionIdAndMessageTypeNotOrderBySequenceNumAsc(
            2L, VoiceInterviewMessageEntity.MESSAGE_TYPE_SUMMARY)).thenReturn(List.of(qMsg, aMsg));
        when(llmProviderRegistry.getChatClientOrDefault("dashscope")).thenReturn(chatClient);
        when(resumeRepository.findById(88L)).thenReturn(Optional.of(resume));
        when(skillService.buildEvaluationReferenceSectionSafe("java-backend")).thenReturn("参考维度");
        when(unifiedEvaluationService.evaluate(
            eq(chatClient), eq("2"), anyList(), eq("负责高并发订单秒杀服务，QPS 5000，实现防超卖。"), eq("参考维度")))
            .thenReturn(report);

        evaluationService.generateEvaluation(2L);

        verify(unifiedEvaluationService).evaluate(
            eq(chatClient), eq("2"), anyList(), eq("负责高并发订单秒杀服务，QPS 5000，实现防超卖。"), eq("参考维度"));
        verify(evaluationRepository).save(org.mockito.ArgumentMatchers.any(VoiceInterviewEvaluationEntity.class));
    }
}
