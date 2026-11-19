package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.modules.recruitment.model.BatchResumeImageExtractDTO;
import interview.guide.modules.recruitment.model.ResumeImageExtractItemDTO;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.MimeType;
import tools.jackson.databind.ObjectMapper;

@DisplayName("批量简历图片联系信息 AI 识别服务测试")
class ResumeImageExtractServiceTest {

  private static final byte[] PNG_HEADER = new byte[] {
      (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01
  };
  private static final byte[] JPEG_HEADER = new byte[] {
      (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10
  };
  private static final byte[] WEBP_HEADER = new byte[] {
      'R', 'I', 'F', 'F', 0x24, 0x00, 0x00, 0x00, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '
  };

  private LlmProviderRegistry llmProviderRegistry;
  private RecruitmentProperties properties;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    llmProviderRegistry = mock(LlmProviderRegistry.class);
    properties = new RecruitmentProperties();
    objectMapper = new ObjectMapper();
  }

  @Test
  @DisplayName("批量上传多张简历图片时保序识别姓名、邮箱与地址，自动清洗全角符号并隔离异常文件")
  void batchExtractsCandidateNameEmailAndLocationWhileIsolatingInvalidFiles() {
    AtomicInteger visionCalls = new AtomicInteger(0);
    ResumeImageExtractService service = new ResumeImageExtractService(
        llmProviderRegistry, properties, objectMapper) {
      @Override
      String invokeVisionModel(MimeType mimeType, byte[] imageBytes) {
        visionCalls.incrementAndGet();
        if ("image/png".equals(mimeType.toString())) {
          return """
              ```json
              {
                "candidateName": "姓名：张三",
                "email": "邮箱：ZhangSan＠Example．com",
                "location": "现居城市：上海·浦东新区",
                "phone": "13800138000"
              }
              ```
              """;
        }
        if ("image/jpeg".equals(mimeType.toString())) {
          return """
              {"candidateName":"李四","email":"lisi@tech.cn","location":"杭州市滨江区","phone":"13912345678"}
              """;
        }
        return """
            {"candidateName":"王五","email":"","location":"深圳市南山区","phone":""}
            """;
      }
    };

    MockMultipartFile img1 = new MockMultipartFile("files", "zhangsan.png", "image/png", PNG_HEADER);
    MockMultipartFile img2 = new MockMultipartFile("files", "lisi.jpg", "image/jpeg", JPEG_HEADER);
    MockMultipartFile img3 = new MockMultipartFile("files", "wangwu.webp", "image/webp", WEBP_HEADER);
    MockMultipartFile pdfMistake = new MockMultipartFile(
        "files", "wrong.pdf", "application/pdf", "%PDF-1.7 fake pdf".getBytes(StandardCharsets.US_ASCII));

    BatchResumeImageExtractDTO result = service.extractBatch(List.of(img1, img2, img3, pdfMistake));

    assertThat(result.totalCount()).isEqualTo(4);
    assertThat(result.validEmailCount()).isEqualTo(2);
    assertThat(visionCalls.get()).isEqualTo(3);

    ResumeImageExtractItemDTO r1 = result.items().get(0);
    assertThat(r1.filename()).isEqualTo("zhangsan.png");
    assertThat(r1.recognized()).isTrue();
    assertThat(r1.candidateName()).isEqualTo("张三");
    assertThat(r1.email()).isEqualTo("zhangsan@example.com");
    assertThat(r1.emailValid()).isTrue();
    assertThat(r1.location()).isEqualTo("上海·浦东新区");
    assertThat(r1.phone()).isEqualTo("13800138000");
    assertThat(r1.error()).isNull();

    ResumeImageExtractItemDTO r2 = result.items().get(1);
    assertThat(r2.filename()).isEqualTo("lisi.jpg");
    assertThat(r2.candidateName()).isEqualTo("李四");
    assertThat(r2.email()).isEqualTo("lisi@tech.cn");
    assertThat(r2.emailValid()).isTrue();
    assertThat(r2.location()).isEqualTo("杭州市滨江区");
    assertThat(r2.error()).isNull();

    ResumeImageExtractItemDTO r3 = result.items().get(2);
    assertThat(r3.filename()).isEqualTo("wangwu.webp");
    assertThat(r3.recognized()).isTrue();
    assertThat(r3.candidateName()).isEqualTo("王五");
    assertThat(r3.email()).isNull();
    assertThat(r3.emailValid()).isFalse();
    assertThat(r3.location()).isEqualTo("深圳市南山区");
    assertThat(r3.error()).contains("未识别到邮箱");

    ResumeImageExtractItemDTO r4 = result.items().get(3);
    assertThat(r4.filename()).isEqualTo("wrong.pdf");
    assertThat(r4.recognized()).isFalse();
    assertThat(r4.emailValid()).isFalse();
    assertThat(r4.error()).contains("文档格式");
  }

  @Test
  @DisplayName("当默认文本模型不支持图片输入报错时，自动回退 DashScope qwen-vl-plus 视觉模型")
  @SuppressWarnings("unchecked")
  void fallsBackToDashscopeVisionModelWhenPrimaryModelRejectsImage() {
    ChatClient failingPrimary = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec primaryPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    when(failingPrimary.prompt()).thenReturn(primaryPrompt);
    when(primaryPrompt.system(anyString())).thenReturn(primaryPrompt);
    when(primaryPrompt.user(any(Consumer.class))).thenReturn(primaryPrompt);
    when(primaryPrompt.options(any())).thenReturn(primaryPrompt);
    when(primaryPrompt.call()).thenThrow(new RuntimeException("Model does not support image_url"));

    ChatClient fallbackVision = mock(ChatClient.class);
    ChatClient.ChatClientRequestSpec fallbackPrompt = mock(ChatClient.ChatClientRequestSpec.class);
    ChatClient.CallResponseSpec fallbackCall = mock(ChatClient.CallResponseSpec.class);
    when(fallbackVision.prompt()).thenReturn(fallbackPrompt);
    when(fallbackPrompt.system(anyString())).thenReturn(fallbackPrompt);
    when(fallbackPrompt.user(any(Consumer.class))).thenReturn(fallbackPrompt);
    when(fallbackPrompt.options(any())).thenReturn(fallbackPrompt);
    when(fallbackPrompt.call()).thenReturn(fallbackCall);
    when(fallbackCall.content()).thenReturn(
        "{\"candidateName\":\"赵六\",\"email\":\"zhaoliu@example.org\",\"location\":\"北京海淀\"}");

    when(llmProviderRegistry.getPlainChatClient(null)).thenReturn(failingPrimary);
    when(llmProviderRegistry.getPlainChatClient("dashscope")).thenReturn(fallbackVision);

    ResumeImageExtractService service = new ResumeImageExtractService(
        llmProviderRegistry, properties, objectMapper);
    MockMultipartFile img = new MockMultipartFile("files", "zhaoliu.png", "image/png", PNG_HEADER);

    BatchResumeImageExtractDTO result = service.extractBatch(List.of(img));

    assertThat(result.validEmailCount()).isEqualTo(1);
    ResumeImageExtractItemDTO item = result.items().get(0);
    assertThat(item.candidateName()).isEqualTo("赵六");
    assertThat(item.email()).isEqualTo("zhaoliu@example.org");
    assertThat(item.location()).isEqualTo("北京海淀");
    assertThat(item.emailValid()).isTrue();
  }

  @Test
  @DisplayName("空文件列表或超出单批上限时直接返回参数错误")
  void rejectsEmptyOrOversizedBatch() {
    properties.setMaxResumeImagesPerBatch(2);
    ResumeImageExtractService service = new ResumeImageExtractService(
        llmProviderRegistry, properties, objectMapper);

    assertThatThrownBy(() -> service.extractBatch(List.of()))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("至少上传一张简历图片");

    MockMultipartFile f1 = new MockMultipartFile("files", "1.png", "image/png", PNG_HEADER);
    MockMultipartFile f2 = new MockMultipartFile("files", "2.png", "image/png", PNG_HEADER);
    MockMultipartFile f3 = new MockMultipartFile("files", "3.png", "image/png", PNG_HEADER);
    assertThatThrownBy(() -> service.extractBatch(List.of(f1, f2, f3)))
        .isInstanceOf(BusinessException.class)
        .hasMessageContaining("单次最多批量上传 2 张");
  }
}
