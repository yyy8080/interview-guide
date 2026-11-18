package interview.guide.modules.mail;

import interview.guide.common.annotation.RateLimit;
import interview.guide.common.result.PageResult;
import interview.guide.common.result.Result;
import interview.guide.common.security.CurrentAdmin;
import interview.guide.modules.mail.model.EmailLogDTO;
import interview.guide.modules.mail.model.EmailLogStatus;
import interview.guide.modules.mail.model.EmailTemplateDTO;
import interview.guide.modules.mail.model.EmailTemplateRequest;
import interview.guide.modules.mail.model.MailSettingsDTO;
import interview.guide.modules.mail.model.MailSettingsRequest;
import interview.guide.modules.mail.model.TestMailRequest;
import interview.guide.modules.mail.model.WeComEmailAccountDTO;
import interview.guide.modules.mail.service.EmailTemplateService;
import interview.guide.modules.mail.service.MailDeliveryService;
import interview.guide.modules.mail.service.MailOutboxService;
import interview.guide.modules.mail.service.MailSettingsService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 邮件配置（仅管理员）：SMTP / 企业微信邮件 API 设置、模板、发送日志
 */
@RestController
@RequestMapping("/api/admin/mail")
@RequiredArgsConstructor
public class AdminMailController {

  private final MailSettingsService settingsService;
  private final EmailTemplateService templateService;
  private final MailOutboxService outboxService;
  private final MailDeliveryService deliveryService;

  @GetMapping("/settings")
  public Result<MailSettingsDTO> getSettings() {
    return Result.success(settingsService.get());
  }

  @PutMapping("/settings")
  public Result<MailSettingsDTO> updateSettings(@Valid @RequestBody MailSettingsRequest request) {
    return Result.success(settingsService.update(request, CurrentAdmin.id()));
  }

  @GetMapping("/wecom/account")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<WeComEmailAccountDTO> queryWeComAccount() {
    return Result.success(deliveryService.queryWeComAccount());
  }

  @PostMapping("/test")
  @RateLimit(dimension = RateLimit.Dimension.GLOBAL, count = 10, interval = 1, timeUnit = RateLimit.TimeUnit.MINUTES)
  public Result<Void> sendTest(@Valid @RequestBody TestMailRequest request) {
    outboxService.sendTest(request.to().trim());
    return Result.success(null);
  }

  @GetMapping("/templates")
  public Result<List<EmailTemplateDTO>> listTemplates() {
    return Result.success(templateService.list());
  }

  @PutMapping("/templates/{code}")
  public Result<EmailTemplateDTO> updateTemplate(@PathVariable String code,
                                                 @Valid @RequestBody EmailTemplateRequest request) {
    return Result.success(templateService.update(code, request, CurrentAdmin.id()));
  }

  @DeleteMapping("/templates/{code}")
  public Result<EmailTemplateDTO> resetTemplate(@PathVariable String code) {
    return Result.success(templateService.reset(code));
  }

  @GetMapping("/logs")
  public Result<PageResult<EmailLogDTO>> listLogs(@RequestParam(required = false) EmailLogStatus status,
                                                  @RequestParam(defaultValue = "1") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
    return Result.success(outboxService.listLogs(status, page, size));
  }

  @PostMapping("/logs/{id}/resend")
  public Result<Void> resend(@PathVariable Long id) {
    outboxService.resend(id);
    return Result.success(null);
  }
}
