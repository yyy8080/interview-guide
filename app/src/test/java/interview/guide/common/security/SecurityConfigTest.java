package interview.guide.common.security;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import interview.guide.modules.auth.model.AdminRole;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.auth.repository.AdminUserRepository;
import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(SpringExtension.class)
@WebAppConfiguration
@ContextConfiguration(classes = SecurityConfigTest.TestSecurityContext.class)
@DisplayName("后台与候选人路由安全权限配置测试")
class SecurityConfigTest {

  private static final String SECRET = "security-config-test-secret-0123456789";

  @Autowired
  private WebApplicationContext context;

  @Autowired
  @Qualifier("springSecurityFilterChain")
  private Filter springSecurityFilterChain;

  @Autowired
  private JwtTokenService jwtTokenService;

  @Autowired
  private AdminUserRepository adminUserRepository;

  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .addFilters(springSecurityFilterChain)
        .build();
  }

  private String issueForRole(Long id, String username, AdminRole role) {
    AdminUserEntity entity = new AdminUserEntity();
    entity.setId(id);
    entity.setUsername(username);
    entity.setRole(role);
    entity.setEnabled(true);
    entity.setTokenVersion(1);
    when(adminUserRepository.findById(id)).thenReturn(Optional.of(entity));
    return jwtTokenService.issue(entity).token();
  }

  @Test
  @DisplayName("公开接口与候选人门户接口免后台 JWT，即使携带过期头也不受后台资源服务器拦截")
  void publicAndCandidatePathsBypassAdminJwt() throws Exception {
    mockMvc.perform(get("/api/public/jobs").header(HttpHeaders.AUTHORIZATION, "Bearer expired-garbage"))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/candidate/applications").header(HttpHeaders.AUTHORIZATION, "Bearer expired-garbage"))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/admin/auth/login"))
        .andExpect(status().isOk());
  }

  @Test
  @DisplayName("未登录或使用候选人令牌访问后台接口返回 401")
  void rejectsUnauthenticatedAndCandidateTokensOnAdminEndpoints() throws Exception {
    mockMvc.perform(get("/api/admin/applications"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));

    String candidateToken = jwtTokenService.issueCandidate(99L, "cand@example.com").token();
    mockMvc.perform(get("/api/admin/applications")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + candidateToken))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("INTERVIEWER 仅允许只读查看候选人、岗位、面试详情及改密，禁止审批与管理写操作")
  void interviewerHasReadOnlyAccess() throws Exception {
    String token = issueForRole(3L, "interviewer01", AdminRole.INTERVIEWER);

    mockMvc.perform(get("/api/admin/applications")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/admin/jobs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/interview/sessions/s-1/details")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk());
    mockMvc.perform(put("/api/admin/auth/password")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isOk());

    mockMvc.perform(post("/api/admin/applications/1/approve")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    mockMvc.perform(post("/api/admin/jobs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/api/admin/mail/settings")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
        .andExpect(status().isForbidden());
  }

  @Test
  @DisplayName("HR 可执行招聘审批与岗位管理，但禁止访问 ADMIN 专属邮件与账号配置")
  void hrCanWriteRecruitmentButNotAdminOnlySettings() throws Exception {
    String hrToken = issueForRole(2L, "hr01", AdminRole.HR);
    String adminToken = issueForRole(1L, "admin", AdminRole.ADMIN);

    mockMvc.perform(post("/api/admin/applications/1/approve")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + hrToken))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/admin/jobs")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + hrToken))
        .andExpect(status().isOk());
    mockMvc.perform(get("/api/admin/mail/settings")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + hrToken))
        .andExpect(status().isForbidden());

    mockMvc.perform(get("/api/admin/mail/settings")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
        .andExpect(status().isOk());
  }

  @Configuration
  @EnableWebMvc
  @Import({SecurityConfig.class, ProbeController.class})
  static class TestSecurityContext {

    @Bean
    SecurityProperties securityProperties() {
      SecurityProperties props = new SecurityProperties();
      props.setJwtSecret(SECRET);
      props.setTokenTtl(Duration.ofHours(1));
      props.setPublicApiDocs(false);
      return props;
    }

    @Bean
    JwtTokenService jwtTokenService(SecurityProperties properties) {
      return new JwtTokenService(properties);
    }

    @Bean
    AdminUserRepository adminUserRepository() {
      return mock(AdminUserRepository.class);
    }

    @Bean
    AdminJwtAuthenticationConverter authenticationConverter(AdminUserRepository repository) {
      return new AdminJwtAuthenticationConverter(repository);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }
  }

  @RestController
  static class ProbeController {

    @GetMapping("/api/public/jobs")
    String publicJobs() {
      return "ok";
    }

    @GetMapping("/api/candidate/applications")
    String candidateApps() {
      return "ok";
    }

    @PostMapping("/api/admin/auth/login")
    String adminLogin() {
      return "ok";
    }

    @PutMapping("/api/admin/auth/password")
    String changePassword() {
      return "ok";
    }

    @GetMapping("/api/admin/applications")
    String listApps() {
      return "ok";
    }

    @PostMapping("/api/admin/applications/{id}/approve")
    String approveApp(@PathVariable Long id) {
      return "ok-" + id;
    }

    @GetMapping("/api/admin/jobs")
    String listJobs() {
      return "ok";
    }

    @PostMapping("/api/admin/jobs")
    String createJob() {
      return "ok";
    }

    @GetMapping("/api/interview/sessions/{id}/details")
    String sessionDetails(@PathVariable String id) {
      return "ok-" + id;
    }

    @GetMapping("/api/admin/mail/settings")
    String mailSettings() {
      return "ok";
    }
  }
}
