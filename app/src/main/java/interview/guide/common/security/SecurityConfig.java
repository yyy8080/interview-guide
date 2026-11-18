package interview.guide.common.security;

import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * 安全配置
 * <ul>
 *   <li>/api/public/**：候选人门户公开接口（岗位浏览、邮箱验证码登录）</li>
 *   <li>/api/candidate/**、/ws/candidate/**：候选人接口，由业务层校验候选人令牌（X-Candidate-Token / candidate_token）</li>
 *   <li>/api/admin/auth/login：后台登录</li>
 *   <li>其余 /api/**、/ws/**：需要后台账号 JWT；面试官（INTERVIEWER）仅可只读访问候选人与面试详情</li>
 * </ul>
 * 认证失败返回 HTTP 401 / 403 + Result，便于前端识别并跳转登录页。
 */
@Slf4j
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  /** 不需要也不解析后台令牌的路径（避免过期令牌导致公开接口 401） */
  static final String[] PUBLIC_PATHS = {
      "/api/public/**",
      "/api/candidate/**",
      "/ws/candidate/**",
      "/api/admin/auth/login",
      "/error",
  };

  static final String[] API_DOC_PATHS = {
      "/swagger-ui.html",
      "/swagger-ui/**",
      "/v3/api-docs/**",
  };

  static final String[] ADMIN_ONLY_PATHS = {
      "/api/llm-provider/**",
      "/api/admin/users/**",
      "/api/admin/mail/**",
      "/actuator/**",
  };

  /** 面试官角色可读（GET）的路径 */
  static final String[] INTERVIEWER_READ_PATHS = {
      "/api/admin/applications/**",
      "/api/admin/jobs/**",
      "/api/interview/sessions/*/details",
      "/api/voice-interview/sessions/*/evaluation",
      "/api/voice-interview/sessions/*/messages",
      "/api/interview-schedule/**",
  };

  static final String WS_PATH_PREFIX = "/ws/";
  static final String WS_TOKEN_PARAM = "access_token";

  private final SecurityProperties securityProperties;
  private final JwtTokenService jwtTokenService;
  private final AdminJwtAuthenticationConverter authenticationConverter;
  private final ObjectMapper objectMapper;

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    http
        .csrf(AbstractHttpConfigurer::disable)
        .cors(Customizer.withDefaults())
        .httpBasic(AbstractHttpConfigurer::disable)
        .formLogin(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(auth -> {
          auth.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll();
          auth.requestMatchers(PUBLIC_PATHS).permitAll();
          if (securityProperties.isPublicApiDocs()) {
            auth.requestMatchers(API_DOC_PATHS).permitAll();
          }
          auth.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll();
          auth.requestMatchers(ADMIN_ONLY_PATHS).hasRole("ADMIN");
          // 面试官：仅可查看候选人、岗位、面试详情与日程，修改个人密码
          auth.requestMatchers(HttpMethod.GET, INTERVIEWER_READ_PATHS).hasAnyRole("ADMIN", "HR", "INTERVIEWER");
          auth.requestMatchers("/api/admin/auth/me", "/api/admin/auth/password")
              .hasAnyRole("ADMIN", "HR", "INTERVIEWER");
          auth.requestMatchers("/api/**", "/ws/**", "/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html")
              .hasAnyRole("ADMIN", "HR");
          auth.anyRequest().denyAll();
        })
        .oauth2ResourceServer(oauth2 -> oauth2
            .bearerTokenResolver(bearerTokenResolver())
            .jwt(jwt -> jwt
                .decoder(jwtTokenService.decoder())
                .jwtAuthenticationConverter(authenticationConverter))
            .authenticationEntryPoint(authenticationEntryPoint())
            .accessDeniedHandler(accessDeniedHandler()))
        .exceptionHandling(ex -> ex
            .authenticationEntryPoint(authenticationEntryPoint())
            .accessDeniedHandler(accessDeniedHandler()));
    return http.build();
  }

  @Bean
  public PasswordEncoder passwordEncoder() {
    return PasswordEncoderFactories.createDelegatingPasswordEncoder();
  }

  /**
   * 令牌解析：
   * <ul>
   *   <li>公开路径不解析令牌</li>
   *   <li>普通请求只接受 Authorization: Bearer 头</li>
   *   <li>WebSocket 握手（浏览器无法设置请求头）额外接受 access_token 查询参数</li>
   * </ul>
   */
  BearerTokenResolver bearerTokenResolver() {
    DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();
    return request -> {
      String path = request.getRequestURI().substring(request.getContextPath().length());
      if (isPublicPath(path)) {
        return null;
      }
      String token = headerResolver.resolve(request);
      if (token != null) {
        return token;
      }
      if (path.startsWith(WS_PATH_PREFIX)) {
        String queryToken = request.getParameter(WS_TOKEN_PARAM);
        if (queryToken != null && !queryToken.isBlank()) {
          return queryToken;
        }
      }
      return null;
    };
  }

  static boolean isPublicPath(String path) {
    return path.startsWith("/api/public/")
        || path.startsWith("/api/candidate/")
        || path.startsWith("/ws/candidate/")
        || path.equals("/api/admin/auth/login");
  }

  AuthenticationEntryPoint authenticationEntryPoint() {
    return (request, response, authException) ->
        writeError(request, response, HttpServletResponse.SC_UNAUTHORIZED,
            ErrorCode.UNAUTHORIZED.getCode(), resolveAuthMessage(authException.getMessage()));
  }

  AccessDeniedHandler accessDeniedHandler() {
    return (request, response, accessDeniedException) ->
        writeError(request, response, HttpServletResponse.SC_FORBIDDEN,
            ErrorCode.FORBIDDEN.getCode(), "没有权限执行该操作");
  }

  private String resolveAuthMessage(String raw) {
    if (raw != null && (raw.contains("失效") || raw.contains("禁用") || raw.contains("不存在"))) {
      return raw;
    }
    return "请先登录";
  }

  private void writeError(HttpServletRequest request, HttpServletResponse response,
                          int httpStatus, int code, String message) throws IOException {
    log.debug("认证/授权失败: path={}, status={}", request.getRequestURI(), httpStatus);
    response.setStatus(httpStatus);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.getWriter().write(objectMapper.writeValueAsString(Result.error(code, message)));
  }
}
