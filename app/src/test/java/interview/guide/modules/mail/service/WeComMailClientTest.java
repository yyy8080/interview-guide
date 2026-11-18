package interview.guide.modules.mail.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.mail.model.MailChannel;
import interview.guide.modules.mail.model.MailSecurity;
import interview.guide.modules.mail.model.WeComEmailAccountDTO;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("企业微信邮件 API 客户端测试")
class WeComMailClientTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private HttpServer server;
  private String baseUrl;
  private WeComMailClient client;

  @BeforeEach
  void setUp() throws IOException {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    HttpClient httpClient = HttpClient.newBuilder()
        .proxy(HttpClient.Builder.NO_PROXY)
        .connectTimeout(Duration.ofSeconds(5))
        .build();
    Clock fixedClock = Clock.fixed(Instant.parse("2026-11-17T12:00:00Z"), ZoneOffset.UTC);
    client = new WeComMailClient(objectMapper, httpClient, fixedClock);
  }

  @AfterEach
  void tearDown() {
    if (server != null) {
      server.stop(0);
    }
  }

  @Test
  @DisplayName("缓存复用 access_token 并通过 compose_send 发送普通邮件及 Base64 编码的 ICS 附件")
  void cachesAccessTokenAndSendsMailWithOptionalIcsAttachment() throws Exception {
    AtomicInteger tokenCalls = new AtomicInteger();
    AtomicInteger sendCalls = new AtomicInteger();
    AtomicReference<String> lastQuery = new AtomicReference<>();
    AtomicReference<String> lastBody = new AtomicReference<>();

    server.createContext("/cgi-bin/gettoken", exchange -> {
      tokenCalls.incrementAndGet();
      String query = exchange.getRequestURI().getRawQuery();
      if ("corpid=ww_corp_01&corpsecret=sec_abc_123".equals(query)) {
        writeJson(exchange, 200, """
            {"errcode":0,"errmsg":"ok","access_token":"tok_valid_001","expires_in":7200}
            """);
      } else {
        writeJson(exchange, 200, """
            {"errcode":40013,"errmsg":"invalid corpid"}
            """);
      }
    });

    server.createContext("/cgi-bin/exmail/app/compose_send", exchange -> {
      sendCalls.incrementAndGet();
      lastQuery.set(exchange.getRequestURI().getRawQuery());
      lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      writeJson(exchange, 200, """
          {"errcode":0,"errmsg":"ok"}
          """);
    });
    server.start();

    ResolvedMailConfig config = wecomConfig(true, "ww_corp_01", "sec_abc_123");

    // 第一次发送普通验证码邮件
    client.sendMail(config, "candidate@example.com", "【星辰科技】邮箱验证码", "您的验证码为 886622", null);

    JsonNode firstPayload = objectMapper.readTree(lastBody.get());
    assertThat(lastQuery.get()).isEqualTo("access_token=tok_valid_001");
    assertThat(firstPayload.path("to").path("emails").get(0).asString()).isEqualTo("candidate@example.com");
    assertThat(firstPayload.path("subject").asString()).isEqualTo("【星辰科技】邮箱验证码");
    assertThat(firstPayload.path("content").asString()).isEqualTo("您的验证码为 886622");
    assertThat(firstPayload.path("content_type").asString()).isEqualTo("text");
    assertThat(firstPayload.has("attachment_list")).isFalse();

    // 第二次发送带线下面试 ICS 附件的通知邮件（应复用缓存的 access_token）
    String icsContent = "BEGIN:VCALENDAR\r\nSUMMARY:线下面试\r\nEND:VCALENDAR\r\n";
    client.sendMail(config, "candidate@example.com", "线下面试邀请", "请按时参加", icsContent);

    assertThat(tokenCalls.get()).isEqualTo(1);
    assertThat(sendCalls.get()).isEqualTo(2);

    JsonNode secondPayload = objectMapper.readTree(lastBody.get());
    JsonNode attachment = secondPayload.path("attachment_list").get(0);
    assertThat(attachment.path("file_name").asString()).isEqualTo("invite.ics");
    String decodedIcs = new String(
        Base64.getDecoder().decode(attachment.path("content").asString()),
        StandardCharsets.UTF_8
    );
    assertThat(decodedIcs).isEqualTo(icsContent);
  }

  @Test
  @DisplayName("当 compose_send 返回 42001 access_token 过期时自动刷新凭证并重试一次")
  void refreshesTokenAndRetriesWhenTokenExpired() {
    AtomicInteger tokenCalls = new AtomicInteger();
    AtomicInteger sendCalls = new AtomicInteger();

    server.createContext("/cgi-bin/gettoken", exchange -> {
      int n = tokenCalls.incrementAndGet();
      writeJson(exchange, 200, """
          {"errcode":0,"errmsg":"ok","access_token":"tok_v%d","expires_in":7200}
          """.formatted(n));
    });

    server.createContext("/cgi-bin/exmail/app/compose_send", exchange -> {
      sendCalls.incrementAndGet();
      String query = exchange.getRequestURI().getRawQuery();
      if ("access_token=tok_v1".equals(query)) {
        writeJson(exchange, 200, """
            {"errcode":42001,"errmsg":"access_token expired"}
            """);
      } else if ("access_token=tok_v2".equals(query)) {
        writeJson(exchange, 200, """
            {"errcode":0,"errmsg":"ok"}
            """);
      } else {
        writeJson(exchange, 200, """
            {"errcode":40014,"errmsg":"unexpected token"}
            """);
      }
    });
    server.start();

    client.sendMail(wecomConfig(true, "ww_corp_01", "sec_abc_123"),
        "candidate@example.com", "测试主题", "测试正文", null);

    assertThat(tokenCalls.get()).isEqualTo(2);
    assertThat(sendCalls.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("支持通过 get_email_alias 查询企业微信应用分配的主邮箱与别名")
  void queriesWeComAppEmailAccount() {
    server.createContext("/cgi-bin/gettoken", exchange -> writeJson(exchange, 200, """
        {"errcode":0,"errmsg":"ok","access_token":"tok_alias_01","expires_in":7200}
        """));
    server.createContext("/cgi-bin/exmail/app/get_email_alias", exchange -> writeJson(exchange, 200, """
        {
          "errcode": 0,
          "errmsg": "ok",
          "email": "recruit-app@company.wecom.work",
          "alias_list": ["hr@company.com", "jobs@company.com"]
        }
        """));
    server.start();

    WeComEmailAccountDTO account = client.queryAppEmailAccount(
        wecomConfig(false, "ww_corp_01", "sec_abc_123"));

    assertThat(account.email()).isEqualTo("recruit-app@company.wecom.work");
    assertThat(account.aliasList()).containsExactly("hr@company.com", "jobs@company.com");
  }

  @Test
  @DisplayName("接口返回业务错误码或未启用发信时抛出 BusinessException 且不泄露密钥")
  void throwsBusinessExceptionWithoutLeakingSecrets() {
    server.createContext("/cgi-bin/gettoken", exchange -> writeJson(exchange, 200, """
        {"errcode":40001,"errmsg":"invalid credential"}
        """));
    server.start();

    assertThatThrownBy(() -> client.sendMail(
        wecomConfig(false, "ww_corp_01", "super_secret_val"),
        "a@b.com", "主题", "正文", null))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.MAIL_NOT_CONFIGURED.getCode());

    assertThatThrownBy(() -> client.sendMail(
        wecomConfig(true, "ww_corp_01", "super_secret_val"),
        "a@b.com", "主题", "正文", null))
        .isInstanceOf(BusinessException.class)
        .satisfies(ex -> {
          BusinessException be = (BusinessException) ex;
          assertThat(be.getCode()).isEqualTo(ErrorCode.MAIL_SEND_FAILED.getCode());
          assertThat(be.getMessage()).contains("40001");
          assertThat(be.getMessage()).doesNotContain("super_secret_val");
        });
  }

  private ResolvedMailConfig wecomConfig(boolean enabled, String corpId, String secret) {
    return new ResolvedMailConfig(
        enabled,
        MailChannel.WECOM_API,
        null,
        null,
        null,
        null,
        MailSecurity.SSL,
        null,
        "星辰招聘",
        corpId,
        secret,
        baseUrl,
        "星辰科技",
        "https://jobs.example.com",
        "hr@example.com"
    );
  }

  private static void writeJson(HttpExchange exchange, int status, String json) throws IOException {
    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
    exchange.sendResponseHeaders(status, bytes.length);
    try (OutputStream os = exchange.getResponseBody()) {
      os.write(bytes);
    }
  }
}
