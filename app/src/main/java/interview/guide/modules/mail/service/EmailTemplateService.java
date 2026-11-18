package interview.guide.modules.mail.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.mail.model.EmailTemplateCode;
import interview.guide.modules.mail.model.EmailTemplateDTO;
import interview.guide.modules.mail.model.EmailTemplateEntity;
import interview.guide.modules.mail.model.EmailTemplateRequest;
import interview.guide.modules.mail.repository.EmailTemplateRepository;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 邮件模板：后台可编辑，未编辑时使用内置默认内容；使用 {变量} 占位符渲染
 */
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

  private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_]*)}");

  private final EmailTemplateRepository repository;

  @Transactional(readOnly = true)
  public List<EmailTemplateDTO> list() {
    return Arrays.stream(EmailTemplateCode.values()).map(this::toDTO).toList();
  }

  @Transactional(readOnly = true)
  public EmailTemplateDTO get(String code) {
    return toDTO(parseCode(code));
  }

  @Transactional(rollbackFor = Exception.class)
  public EmailTemplateDTO update(String code, EmailTemplateRequest request, Long operatorId) {
    EmailTemplateCode templateCode = parseCode(code);
    EmailTemplateEntity entity = repository.findById(templateCode.name()).orElseGet(() -> {
      EmailTemplateEntity created = new EmailTemplateEntity();
      created.setCode(templateCode.name());
      return created;
    });
    entity.setSubject(request.subject().trim());
    entity.setBody(request.body());
    // 验证码邮件是登录必需链路，不允许停用
    entity.setEnabled(templateCode == EmailTemplateCode.VERIFICATION_CODE || request.enabled());
    entity.setUpdatedBy(operatorId);
    repository.save(entity);
    return toDTO(templateCode);
  }

  /** 恢复为内置默认模板 */
  @Transactional(rollbackFor = Exception.class)
  public EmailTemplateDTO reset(String code) {
    EmailTemplateCode templateCode = parseCode(code);
    repository.deleteById(templateCode.name());
    return toDTO(templateCode);
  }

  /**
   * 渲染模板；模板被停用时返回 enabled=false（调用方跳过发送）
   */
  @Transactional(readOnly = true)
  public RenderedMail render(EmailTemplateCode code, Map<String, String> variables) {
    EmailTemplateEntity entity = repository.findById(code.name()).orElse(null);
    String subject = entity != null ? entity.getSubject() : code.defaultSubject();
    String body = entity != null ? entity.getBody() : code.defaultBody();
    boolean enabled = entity == null || entity.isEnabled() || code == EmailTemplateCode.VERIFICATION_CODE;
    return new RenderedMail(code, enabled, replace(subject, variables).replaceAll("[\\r\\n]+", " ").trim(),
        replace(body, variables));
  }

  /**
   * 替换 {变量}；未提供的变量保持原样，便于发现模板配置问题
   */
  public static String replace(String template, Map<String, String> variables) {
    if (template == null || template.isEmpty()) {
      return "";
    }
    Matcher matcher = PLACEHOLDER.matcher(template);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String key = matcher.group(1);
      String value = variables != null ? variables.get(key) : null;
      matcher.appendReplacement(result, Matcher.quoteReplacement(value != null ? value : matcher.group(0)));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private EmailTemplateDTO toDTO(EmailTemplateCode code) {
    EmailTemplateEntity entity = repository.findById(code.name()).orElse(null);
    List<String> variables = new ArrayList<>(EmailTemplateCode.COMMON_VARIABLES);
    variables.addAll(code.variables());
    if (entity == null) {
      return new EmailTemplateDTO(code.name(), code.displayName(), code.defaultSubject(), code.defaultBody(),
          true, false, variables, null);
    }
    return new EmailTemplateDTO(code.name(), code.displayName(), entity.getSubject(), entity.getBody(),
        entity.isEnabled(), true, variables, entity.getUpdatedAt());
  }

  private EmailTemplateCode parseCode(String code) {
    try {
      return EmailTemplateCode.valueOf(code);
    } catch (IllegalArgumentException | NullPointerException e) {
      throw new BusinessException(ErrorCode.EMAIL_TEMPLATE_NOT_FOUND);
    }
  }

  public record RenderedMail(EmailTemplateCode code, boolean enabled, String subject, String body) {
  }
}
