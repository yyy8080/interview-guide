package interview.guide.modules.voiceinterview.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import interview.guide.common.ai.PromptSanitizer;
import interview.guide.common.config.LlmProviderProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("VoiceInterviewPromptService 定位与上下文提示词测试")
class VoiceInterviewPromptServiceTest {

  private final VoiceInterviewPromptService service =
      new VoiceInterviewPromptService(new PromptSanitizer(mock(LlmProviderProperties.class)));

  @Test
  @DisplayName("根据面试者自选定位（初级 / 中级 / 高级）注入差异化语音提问深度指令")
  void injectsCandidateLevelGuidanceIntoVoiceSystemPrompt() {
    String juniorPrompt = service.generateSystemPromptWithContext(
        "custom", "熟悉 Java 与 Spring Boot", "负责后端微服务开发", "junior");
    assertThat(juniorPrompt)
        .contains("企业招聘面试官")
        .contains("当前面试者自选定位为【初级】")
        .contains("避免超大规模分布式架构")
        .contains("负责后端微服务开发")
        .contains("熟悉 Java 与 Spring Boot");

    String midPrompt = service.generateSystemPromptWithContext("java-backend", null, null, "mid");
    assertThat(midPrompt)
        .contains("java-backend")
        .contains("当前面试者自选定位为【中级】")
        .contains("知其然更知其所以然");

    String seniorPrompt = service.generateSystemPromptWithContext("java-backend", null, null, "senior");
    assertThat(seniorPrompt)
        .contains("当前面试者自选定位为【高级】")
        .contains("系统架构设计权衡")
        .contains("减少纯概念背诵题");
  }
}
