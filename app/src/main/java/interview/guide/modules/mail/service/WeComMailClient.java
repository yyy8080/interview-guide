package interview.guide.modules.mail.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.mail.model.WeComEmailAccountDTO;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 企业微信邮件 API 客户端。
 * <ul>
 *   <li>获取并缓存 {@code access_token}：{@code GET /cgi-bin/gettoken}</li>
 *   <li>发送邮件（含可选 {@code invite.ics} 附件）：{@code POST /cgi-bin/exmail/app/compose_send}</li>
 *   <li>查询应用邮箱主账号与别名：{@code POST /cgi-bin/exmail/app/get_email_alias}</li>
 * </ul>
 * 参考文档：<a href="https://developer.work.weixin.qq.com/document/path/95486">企业微信邮件 API 概述</a>
 */
@Slf4j
@Component
public class WeComMailClient {

  public static final String DEFAULT_BASE_URL = "https://qyapi.weixin.qq.com";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
  private static final long TOKEN_REFRESH_LEAD_SECONDS = 300L;
  private static final String ICS_FILE_NAME = "invite.ics";

  private final ObjectMapper objectMapper;
  private final HttpClient httpClient;
  private final Clock clock;
  private final ConcurrentHashMap<String, CachedToken> tokenCache = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, Object> tokenLocks = new ConcurrentHashMap<>();

  @Autowired
  public WeComMailClient(ObjectMapper objectMapper) {
    this(
        objectMapper,
        HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(),
        Clock.systemUTC()
    );
  }

  WeComMailClient(ObjectMapper objectMapper, HttpClient httpClient, Clock clock) {
    this.objectMapper = objectMapper;
    this.httpClient = httpClient;
    this.clock = clock;
  }

  /**
   * 通过企业微信应用发送邮件（调用接口的应用自动成为发件人）
   */
  public void sendMail(ResolvedMailConfig config, String to, String subject, String body, String ics) {
    if (!config.enabled()) {
      throw new BusinessException(ErrorCode.MAIL_NOT_CONFIGURED);
    }
    validateCredentials(config);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("to", Map.of("emails", List.of(to)));
    payload.put("subject", subject == null ? "" : subject);
    payload.put("content", body == null ? "" : body);
    payload.put("content_type", "text");
    if (ics != null && !ics.isBlank()) {
      String encodedIcs = Base64.getEncoder().encodeToString(ics.getBytes(StandardCharsets.UTF_8));
      payload.put("attachment_list", List.of(Map.of(
          "file_name", ICS_FILE_NAME,
          "content", encodedIcs
      )));
    }

    String requestJson = serializeJson(payload);
    JsonNode response = postWithTokenRetry(config, "/cgi-bin/exmail/app/compose_send", requestJson);
    int errcode = response.path("errcode").asInt(-1);
    if (errcode != 0) {
      String errmsg = response.path("errmsg").asString("unknown error");
      log.warn("企业微信邮件发送返回错误: to={}, errcode={}, errmsg={}",
          MailDeliveryService.maskEmail(to), errcode, errmsg);
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED,
          "企业微信邮件发送失败(" + errcode + "): " + summarizeText(errmsg));
    }
  }

  /**
   * 查询企业微信应用分配的主发信邮箱与别名邮箱（可用于连通性校验）
   */
  public WeComEmailAccountDTO queryAppEmailAccount(ResolvedMailConfig config) {
    validateCredentials(config);
    JsonNode response = postWithTokenRetry(config, "/cgi-bin/exmail/app/get_email_alias", "{}");
    int errcode = response.path("errcode").asInt(-1);
    if (errcode != 0) {
      String errmsg = response.path("errmsg").asString("unknown error");
      log.warn("查询企业微信应用邮箱失败: corpId={}, errcode={}, errmsg={}",
          config.wecomCorpId(), errcode, errmsg);
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED,
          "查询企业微信应用邮箱失败(" + errcode + "): " + summarizeText(errmsg));
    }
    String email = response.path("email").asString("");
    List<String> aliases = new ArrayList<>();
    JsonNode aliasNode = response.path("alias_list");
    if (aliasNode.isArray()) {
      for (JsonNode item : aliasNode) {
        String alias = item.asString("").trim();
        if (!alias.isEmpty()) {
          aliases.add(alias);
        }
      }
    }
    return new WeComEmailAccountDTO(email, List.copyOf(aliases));
  }

  /** 清空内存中的 access_token 缓存（配置变更或测试时可调用） */
  public void clearTokenCache() {
    tokenCache.clear();
  }

  private JsonNode postWithTokenRetry(ResolvedMailConfig config, String apiPath, String requestJson) {
    String baseUrl = resolveBaseUrl(config.wecomApiBaseUrl());
    String cacheKey = buildCacheKey(baseUrl, config.wecomCorpId(), config.wecomSecret());

    String token = getAccessToken(baseUrl, config.wecomCorpId(), config.wecomSecret(), cacheKey, false);
    JsonNode response = executePost(baseUrl, apiPath, token, requestJson);
    int errcode = response.path("errcode").asInt(0);
    if (isTokenInvalidError(errcode)) {
      log.info("企业微信 access_token 已失效(errcode={})，刷新后重试: corpId={}", errcode, config.wecomCorpId());
      token = getAccessToken(baseUrl, config.wecomCorpId(), config.wecomSecret(), cacheKey, true);
      response = executePost(baseUrl, apiPath, token, requestJson);
    }
    return response;
  }

  private String getAccessToken(String baseUrl, String corpId, String corpSecret, String cacheKey,
                                boolean forceRefresh) {
    Instant now = clock.instant();
    if (!forceRefresh) {
      CachedToken cached = tokenCache.get(cacheKey);
      if (cached != null && cached.expiresAt().isAfter(now)) {
        return cached.accessToken();
      }
    } else {
      tokenCache.remove(cacheKey);
    }

    Object lock = tokenLocks.computeIfAbsent(cacheKey, ignored -> new Object());
    synchronized (lock) {
      Instant currentNow = clock.instant();
      if (!forceRefresh) {
        CachedToken cached = tokenCache.get(cacheKey);
        if (cached != null && cached.expiresAt().isAfter(currentNow)) {
          return cached.accessToken();
        }
      }
      CachedToken refreshed = fetchAccessToken(baseUrl, corpId, corpSecret);
      tokenCache.put(cacheKey, refreshed);
      return refreshed.accessToken();
    }
  }

  private CachedToken fetchAccessToken(String baseUrl, String corpId, String corpSecret) {
    URI uri = URI.create(baseUrl + "/cgi-bin/gettoken?corpid=" + urlEncode(corpId)
        + "&corpsecret=" + urlEncode(corpSecret));
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/json")
        .GET()
        .build();

    JsonNode body = sendRequest(request, "/cgi-bin/gettoken");
    int errcode = body.path("errcode").asInt(-1);
    String token = body.path("access_token").asString("");
    if (errcode != 0 || token.isBlank()) {
      String errmsg = body.path("errmsg").asString("unknown error");
      log.warn("获取企业微信 access_token 失败: corpId={}, errcode={}, errmsg={}", corpId, errcode, errmsg);
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED,
          "获取企业微信 access_token 失败(" + errcode + "): " + summarizeText(errmsg));
    }
    long expiresIn = body.path("expires_in").asLong(7200L);
    long cacheSeconds = expiresIn > TOKEN_REFRESH_LEAD_SECONDS
        ? expiresIn - TOKEN_REFRESH_LEAD_SECONDS
        : Math.max(1L, expiresIn / 2);
    return new CachedToken(token, clock.instant().plusSeconds(cacheSeconds));
  }

  private JsonNode executePost(String baseUrl, String apiPath, String accessToken, String requestJson) {
    URI uri = URI.create(baseUrl + apiPath + "?access_token=" + urlEncode(accessToken));
    HttpRequest request = HttpRequest.newBuilder(uri)
        .timeout(REQUEST_TIMEOUT)
        .header("Content-Type", "application/json; charset=UTF-8")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
        .build();
    return sendRequest(request, apiPath);
  }

  private JsonNode sendRequest(HttpRequest request, String endpointLabel) {
    HttpResponse<String> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED, "调用企业微信接口被中断: " + endpointLabel);
    } catch (IOException e) {
      log.warn("调用企业微信接口网络异常: endpoint={}, error={}", endpointLabel, e.getClass().getSimpleName());
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED,
          "调用企业微信接口失败(" + endpointLabel + "): 网络连接异常");
    }

    if (response.statusCode() < 200 || response.statusCode() >= 300) {
      log.warn("企业微信接口返回非 2xx 状态码: endpoint={}, status={}", endpointLabel, response.statusCode());
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED,
          "企业微信接口响应异常(" + endpointLabel + "): HTTP " + response.statusCode());
    }

    try {
      return objectMapper.readTree(response.body());
    } catch (Exception e) {
      log.warn("解析企业微信接口响应 JSON 失败: endpoint={}", endpointLabel);
      throw new BusinessException(ErrorCode.MAIL_SEND_FAILED,
          "解析企业微信接口响应失败(" + endpointLabel + ")");
    }
  }

  private String serializeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.INTERNAL_ERROR, "序列化企业微信邮件请求失败");
    }
  }

  static void validateCredentials(ResolvedMailConfig config) {
    if (config.wecomCorpId() == null || config.wecomCorpId().isBlank()
        || config.wecomSecret() == null || config.wecomSecret().isBlank()) {
      throw new BusinessException(ErrorCode.MAIL_CONFIG_INVALID, "企业微信 CorpID 与邮件应用 Secret 不能为空");
    }
    String baseUrl = resolveBaseUrl(config.wecomApiBaseUrl());
    if (!baseUrl.startsWith("https://") && !baseUrl.startsWith("http://")) {
      throw new BusinessException(ErrorCode.MAIL_CONFIG_INVALID, "企业微信 API 地址必须以 https:// 或 http:// 开头");
    }
  }

  static String resolveBaseUrl(String configuredBaseUrl) {
    if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
      return DEFAULT_BASE_URL;
    }
    String trimmed = configuredBaseUrl.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed.isEmpty() ? DEFAULT_BASE_URL : trimmed;
  }

  private static boolean isTokenInvalidError(int errcode) {
    // 40001: invalid credential, 40014: invalid access_token, 42001: access_token expired
    return errcode == 40001 || errcode == 40014 || errcode == 42001;
  }

  private static String buildCacheKey(String baseUrl, String corpId, String corpSecret) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest((baseUrl + "|" + corpId + "|" + corpSecret).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String summarizeText(String text) {
    if (text == null || text.isBlank()) {
      return "未知错误";
    }
    String cleaned = text.replaceAll("[\\r\\n]+", " ").trim();
    return cleaned.length() > 200 ? cleaned.substring(0, 200) : cleaned;
  }

  private record CachedToken(String accessToken, Instant expiresAt) {
  }
}
