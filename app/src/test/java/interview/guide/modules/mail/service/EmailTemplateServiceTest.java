package interview.guide.modules.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.mail.model.EmailTemplateCode;
import interview.guide.modules.mail.model.EmailTemplateEntity;
import interview.guide.modules.mail.model.EmailTemplateRequest;
import interview.guide.modules.mail.repository.EmailTemplateRepository;
import interview.guide.modules.mail.service.EmailTemplateService.RenderedMail;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("邮件模板渲染与管理服务测试")
class EmailTemplateServiceTest {

  @Mock
  private EmailTemplateRepository repository;

  private EmailTemplateService service;

  @BeforeEach
  void setUp() {
    service = new EmailTemplateService(repository);
  }

  @Test
  @DisplayName("占位符替换支持特殊正则字符且保留未传入变量")
  void replacesPlaceholdersSafely() {
    String template = "您好 {candidateName}，薪资范围 {salary}，未知变量 {missing}";
    String rendered = EmailTemplateService.replace(template, Map.of(
        "candidateName", "王五",
        "salary", "$30k-$45k\\month"
    ));

    assertThat(rendered).isEqualTo("您好 王五，薪资范围 $30k-$45k\\month，未知变量 {missing}");
  }

  @Test
  @DisplayName("未自定义时回退内置默认模板，停用非验证码模板时返回 enabled=false")
  void rendersDefaultAndRespectsDisabledFlag() {
    when(repository.findById(EmailTemplateCode.REJECTED.name())).thenReturn(Optional.empty());

    RenderedMail defaultRejected = service.render(EmailTemplateCode.REJECTED, Map.of(
        "companyName", "远航科技",
        "candidateName", "赵六",
        "jobTitle", "后端工程师",
        "rejectReason", "\n\n简历初筛未通过原因：缺少 Java 后端项目经验",
        "portalUrl", "https://jobs.yuanhang.com",
        "contactInfo", "hr@yuanhang.com"
    ));
    assertThat(defaultRejected.enabled()).isTrue();
    assertThat(defaultRejected.subject()).isEqualTo("【远航科技】投递结果通知 - 后端工程师");
    assertThat(defaultRejected.body()).contains("赵六，您好");
    assertThat(defaultRejected.body()).contains("简历初筛未通过原因：缺少 Java 后端项目经验");

    EmailTemplateEntity disabled = new EmailTemplateEntity();
    disabled.setCode(EmailTemplateCode.REJECTED.name());
    disabled.setSubject("自定义主题\r\n换行清洗");
    disabled.setBody("自定义正文");
    disabled.setEnabled(false);
    when(repository.findById(EmailTemplateCode.REJECTED.name())).thenReturn(Optional.of(disabled));

    RenderedMail customDisabled = service.render(EmailTemplateCode.REJECTED, Map.of());
    assertThat(customDisabled.enabled()).isFalse();
    assertThat(customDisabled.subject()).isEqualTo("自定义主题 换行清洗");
  }

  @Test
  @DisplayName("邮箱验证码模板作为候选人登录必需凭证不允许被停用，非法编码抛出异常")
  void preventsDisablingVerificationCodeTemplate() {
    when(repository.findById(EmailTemplateCode.VERIFICATION_CODE.name())).thenReturn(Optional.empty());
    when(repository.save(any(EmailTemplateEntity.class))).thenAnswer(inv -> inv.getArgument(0));

    service.update("VERIFICATION_CODE", new EmailTemplateRequest("验证码", "您的验证码：{code}", false), 1L);

    ArgumentCaptor<EmailTemplateEntity> captor = ArgumentCaptor.forClass(EmailTemplateEntity.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().isEnabled()).isTrue();

    assertThatThrownBy(() -> service.get("NON_EXISTENT"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.EMAIL_TEMPLATE_NOT_FOUND.getCode());
  }
}
