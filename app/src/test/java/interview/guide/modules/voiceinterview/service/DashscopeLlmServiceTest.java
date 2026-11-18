package interview.guide.modules.voiceinterview.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import interview.guide.common.config.LlmProviderProperties;
import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.ai.PromptSanitizer;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DashscopeLlmService 日志隐私测试")
class DashscopeLlmServiceTest {

  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private VoiceInterviewPromptService promptService;
  @Mock
  private ResumeRepository resumeRepository;

  private final VoiceInterviewProperties properties = new VoiceInterviewProperties();
  private final PromptSanitizer promptSanitizer = new PromptSanitizer(mock(LlmProviderProperties.class));

  private DashscopeLlmService service;

  private ListAppender<ILoggingEvent> logAppender;
  private Logger serviceLogger;

  @BeforeEach
  void setUp() {
    service = new DashscopeLlmService(
        llmProviderRegistry, promptService, resumeRepository, properties, promptSanitizer);
    serviceLogger = (Logger) LoggerFactory.getLogger(DashscopeLlmService.class);
    logAppender = new ListAppender<>();
    logAppender.start();
    serviceLogger.addAppender(logAppender);
  }

  @AfterEach
  void tearDown() {
    serviceLogger.detachAppender(logAppender);
  }

  private String capturedLogs() {
    StringBuilder sb = new StringBuilder();
    for (ILoggingEvent event : logAppender.list) {
      sb.append(event.getFormattedMessage()).append('\n');
    }
    return sb.toString();
  }

  private ILoggingEvent findEvent(Level level, String keyword) {
    return logAppender.list.stream()
        .filter(event -> event.getLevel() == level && event.getFormattedMessage().contains(keyword))
        .findFirst()
        .orElse(null);
  }

  @Test
  @DisplayName("同步调用日志不包含用户输入与模型回答原文，只记录长度")
  void shouldNotLogUserInputOrModelReply() {
    String userInputMarker = "用户敏感标记MARKER-USER-7f3a";
    String replyMarker = "回答敏感标记MARKER-REPLY-9b2c";
    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
    ChatResponse chatResponse = mock(ChatResponse.class);
    Generation generation = mock(Generation.class);
    when(promptService.generateSystemPromptWithContext(anyString(), isNull(), isNull(), anyString()))
        .thenReturn("系统提示");
    when(llmProviderRegistry.getVoiceChatClient("dashscope")).thenReturn(chatClient);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system(anyString())).thenReturn(requestSpec);
    when(requestSpec.user(anyString())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponse);
    when(callResponse.chatResponse()).thenReturn(chatResponse);
    when(chatResponse.getResult()).thenReturn(generation);
    when(generation.getOutput()).thenReturn(new AssistantMessage(replyMarker));

    VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
    String reply = service.chat(userInputMarker, session, List.of());

    assertThat(reply).contains(replyMarker);
    String logs = capturedLogs();
    assertThat(logs).doesNotContain(userInputMarker);
    assertThat(logs).doesNotContain(replyMarker);
    ILoggingEvent replyEvent = findEvent(Level.INFO, "replyLength");
    assertThat(replyEvent).isNotNull();
    assertThat(replyEvent.getFormattedMessage()).contains("replyLength=" + replyMarker.length());
  }

  @Test
  @DisplayName("绑定候选人简历时生成针对简历具体项目的首轮真实性核验提问，未绑定简历时返回 null")
  void generatesResumeTargetedOpeningQuestionWhenResumePresent() {
    VoiceInterviewSessionEntity noResumeSession = new VoiceInterviewSessionEntity();
    assertThat(service.generateResumeOpeningQuestion(noResumeSession)).isNull();

    VoiceInterviewSessionEntity session = new VoiceInterviewSessionEntity();
    session.setId(9L);
    session.setResumeId(77L);
    session.setSkillId("java-backend");
    session.setLlmProvider("dashscope");
    session.setDifficulty("mid");

    interview.guide.modules.resume.model.ResumeEntity resume = new interview.guide.modules.resume.model.ResumeEntity();
    resume.setId(77L);
    resume.setResumeText("主导智能风控规则引擎重构，规则评估耗时降低 65%。");

    ChatClient chatClient = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec callResponse = mock(ChatClient.CallResponseSpec.class);
    ChatResponse chatResponse = mock(ChatResponse.class);
    Generation generation = mock(Generation.class);

    when(resumeRepository.findById(77L)).thenReturn(java.util.Optional.of(resume));
    when(promptService.generateSystemPromptWithContext("java-backend", resume.getResumeText(), null, "mid"))
        .thenReturn("系统提示-含简历核验");
    when(llmProviderRegistry.getVoiceChatClient("dashscope")).thenReturn(chatClient);
    when(chatClient.prompt()).thenReturn(requestSpec);
    when(requestSpec.system("系统提示-含简历核验")).thenReturn(requestSpec);
    when(requestSpec.user(anyString())).thenReturn(requestSpec);
    when(requestSpec.call()).thenReturn(callResponse);
    when(callResponse.chatResponse()).thenReturn(chatResponse);
    when(chatResponse.getResult()).thenReturn(generation);
    when(generation.getOutput()).thenReturn(new AssistantMessage(
        "你好，我是本场面试官，已经看过你的简历。看到你主导了智能风控规则引擎重构并将耗时降低 65%，能具体讲讲你独立负责的核心模块和优化手段吗？"));

    String opening = service.generateResumeOpeningQuestion(session);
    assertThat(opening)
        .contains("智能风控规则引擎重构")
        .contains("独立负责的核心模块");
  }
}
