package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.auth.repository.AdminUserRepository;
import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.model.JobStatus;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.mail.model.EmailLogEntity;
import interview.guide.modules.mail.model.EmailLogStatus;
import interview.guide.modules.mail.model.EmailTemplateCode;
import interview.guide.modules.mail.model.MailSecurity;
import interview.guide.modules.mail.repository.EmailLogRepository;
import interview.guide.modules.mail.service.MailOutboxService;
import interview.guide.modules.mail.service.MailSettingsService;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.BatchInvitationResultDTO;
import interview.guide.modules.recruitment.model.CandidateEntity;
import interview.guide.modules.recruitment.model.CandidateJobInvitationStatusDTO;
import interview.guide.modules.recruitment.model.CreateInvitationsRequest;
import interview.guide.modules.recruitment.model.InvitationListItemDTO;
import interview.guide.modules.recruitment.model.InvitationParticipationFilter;
import interview.guide.modules.recruitment.model.InvitationParticipationStatus;
import interview.guide.modules.recruitment.model.InvitationRecordStatus;
import interview.guide.modules.recruitment.model.InvitationStatsDTO;
import interview.guide.modules.recruitment.model.RecruitmentInvitationEntity;
import interview.guide.modules.recruitment.model.WrittenTestEntity;
import interview.guide.modules.recruitment.model.WrittenTestStatus;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.CandidateRepository;
import interview.guide.modules.recruitment.repository.RecruitmentInvitationRepository;
import interview.guide.modules.recruitment.repository.WrittenTestRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("定向面试邀请、参面状态跟踪与受邀邮箱准入服务测试")
class RecruitmentInvitationServiceTest {

  @Mock
  private RecruitmentInvitationRepository invitationRepository;

  @Mock
  private JobPositionRepository jobPositionRepository;

  @Mock
  private CandidateRepository candidateRepository;

  @Mock
  private ApplicationRepository applicationRepository;

  @Mock
  private WrittenTestRepository writtenTestRepository;

  @Mock
  private EmailLogRepository emailLogRepository;

  @Mock
  private AdminUserRepository adminUserRepository;

  @Mock
  private MailOutboxService mailOutboxService;

  @Mock
  private MailSettingsService mailSettingsService;

  private RecruitmentProperties properties;
  private RecruitmentInvitationService service;

  @BeforeEach
  void setUp() {
    properties = new RecruitmentProperties();
    service = new RecruitmentInvitationService(
        invitationRepository, jobPositionRepository, candidateRepository,
        applicationRepository, writtenTestRepository, emailLogRepository,
        adminUserRepository, mailOutboxService, mailSettingsService, properties);
  }

  private JobPositionEntity openJob(Long id) {
    JobPositionEntity job = new JobPositionEntity();
    job.setId(id);
    job.setTitle("高级后端工程师");
    job.setStatus(JobStatus.OPEN);
    job.setInterviewMode(InterviewMode.BOTH);
    job.setWrittenTestEnabled(true);
    return job;
  }

  private ResolvedMailConfig mailConfig() {
    return new ResolvedMailConfig(true, "smtp.example.com", 465, "hr@example.com", "pwd",
        MailSecurity.SSL, "hr@example.com", "招聘团队", "星辰科技", "https://jobs.example.com/", "hr@example.com");
  }

  @Test
  @DisplayName("批量发送邀请支持多分隔符解析、去重、隔离非法邮箱，并直投候选人邮箱附带专属跳转链接")
  void batchSendInvitationsDeduplicatesAndEnqueuesDirectEmails() {
    JobPositionEntity job = openJob(9L);
    when(jobPositionRepository.findById(9L)).thenReturn(Optional.of(job));
    when(jobPositionRepository.findAllById(List.of(9L))).thenReturn(List.of(job));
    when(mailSettingsService.resolve()).thenReturn(mailConfig());
    when(invitationRepository.findByJobIdAndEmail(eq(9L), any())).thenReturn(Optional.empty());
    when(candidateRepository.findByEmail(any())).thenReturn(Optional.empty());

    AtomicLong idSeq = new AtomicLong(100L);
    when(invitationRepository.save(any(RecruitmentInvitationEntity.class))).thenAnswer(inv -> {
      RecruitmentInvitationEntity e = inv.getArgument(0);
      if (e.getId() == null) {
        e.setId(idSeq.incrementAndGet());
      }
      return e;
    });
    when(mailOutboxService.enqueue(any(), any(), eq(EmailTemplateCode.CANDIDATE_INVITATION), any(), any()))
        .thenReturn(Optional.of(701L));

    EmailLogEntity sentLog = new EmailLogEntity();
    sentLog.setId(701L);
    sentLog.setStatus(EmailLogStatus.SENT);
    when(emailLogRepository.findAllById(List.of(701L))).thenReturn(List.of(sentLog));
    when(adminUserRepository.findAllById(List.of(1L))).thenReturn(List.of());

    CreateInvitationsRequest req = new CreateInvitationsRequest(
        9L,
        List.of(" Alice@Example.com "),
        "alice@example.com;\nbob@example.com, bad-email-format",
        null,
        10,
        false,
        "请在周五前完成作答"
    );

    BatchInvitationResultDTO res = service.sendInvitations(req, 1L);

    assertThat(res.createdCount()).isEqualTo(2);
    assertThat(res.resentCount()).isEqualTo(0);
    assertThat(res.mailEnabled()).isTrue();
    assertThat(res.failed()).hasSize(1);
    assertThat(res.failed().get(0).email()).isEqualTo("bad-email-format");
    assertThat(res.invitations()).hasSize(2);
    assertThat(res.invitations().get(0).emailStatus()).isEqualTo(EmailLogStatus.SENT);
    assertThat(res.invitations().get(0).participated()).isFalse();
    assertThat(res.invitations().get(0).participationStatus()).isEqualTo(InvitationParticipationStatus.NOT_APPLIED);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mailOutboxService, times(2))
        .enqueue(any(), any(), eq(EmailTemplateCode.CANDIDATE_INVITATION), varsCaptor.capture(), eq(null));
    Map<String, String> aliceVars = varsCaptor.getAllValues().get(0);
    assertThat(aliceVars.get("candidateEmail")).isEqualTo("alice@example.com");
    assertThat(aliceVars.get("inviteUrl"))
        .isEqualTo("https://jobs.example.com/careers/login?email=alice%40example.com&redirect=%2Fcareers%2F9");
    assertThat(aliceVars.get("hrNote")).contains("请在周五前完成作答");
  }

  @Test
  @DisplayName("受邀邮箱准入严格拦截非邀请邮箱、跨岗位未受邀投递、过期邀请与已撤销邀请")
  void enforcesInviteOnlyAcrossAuthApplyAndInterview() {
    // 1. 完全未受邀邮箱禁止登录获取验证码
    when(invitationRepository.findByEmailOrderByInvitedAtDesc("uninvited@example.com")).thenReturn(List.of());
    assertThatThrownBy(() -> service.assertCanAuthenticate("uninvited@example.com"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.CANDIDATE_NOT_INVITED.getCode());

    // 2. 受邀岗位 9 的候选人允许登录，但禁止投递未受邀的岗位 88
    RecruitmentInvitationEntity job9Invite = new RecruitmentInvitationEntity();
    job9Invite.setId(1L);
    job9Invite.setJobId(9L);
    job9Invite.setEmail("alice@example.com");
    job9Invite.setStatus(InvitationRecordStatus.ACTIVE);
    job9Invite.setExpiresAt(LocalDateTime.now().plusDays(5));

    when(invitationRepository.findByEmailOrderByInvitedAtDesc("alice@example.com")).thenReturn(List.of(job9Invite));
    service.assertCanAuthenticate("alice@example.com");

    CandidateEntity alice = new CandidateEntity();
    alice.setId(50L);
    alice.setEmail("alice@example.com");
    when(candidateRepository.findById(50L)).thenReturn(Optional.of(alice));
    when(invitationRepository.findByJobIdAndEmail(9L, "alice@example.com")).thenReturn(Optional.of(job9Invite));
    when(invitationRepository.findByJobIdAndEmail(88L, "alice@example.com")).thenReturn(Optional.empty());

    assertThat(service.assertCanApplyAndGet(9L, 50L, true)).isPresent();
    assertThatThrownBy(() -> service.assertCanApplyAndGet(88L, 50L, true))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.CANDIDATE_NOT_INVITED.getCode());

    CandidateJobInvitationStatusDTO statusFor88 = service.checkCandidateJobStatus(88L, 50L);
    assertThat(statusFor88.canApply()).isFalse();
    assertThat(statusFor88.invited()).isFalse();

    // 3. HR 撤销邀请后禁止进入在线面试
    job9Invite.setStatus(InvitationRecordStatus.REVOKED);
    ApplicationEntity app = new ApplicationEntity();
    app.setId(300L);
    app.setJobId(9L);
    app.setCandidateId(50L);
    assertThatThrownBy(() -> service.assertCanAccessInterview(app))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.CANDIDATE_NOT_INVITED.getCode());
    assertThat(service.canAccessInterviewQuietly(app)).isFalse();
  }

  @Test
  @DisplayName("后台统计与列表精确计算对应面试者是否已参加面试（已完成 / 笔试面试中 / 待开考 / 未投递 / 初筛未过 / 已撤销）")
  void computesParticipationStatusAndStatsAccurately() {
    JobPositionEntity job = openJob(9L);
    LocalDateTime future = LocalDateTime.now().plusDays(5);

    // #1 已完成面试
    RecruitmentInvitationEntity i1 = invite(1L, 9L, "done@example.com", InvitationRecordStatus.ACTIVE, future, 101L);
    ApplicationEntity a1 = app(101L, 9L, ApplicationStatus.AI_PASSED, InterviewMode.VOICE, 86);

    // #2 在线笔试进行中（算已参加）
    RecruitmentInvitationEntity i2 = invite(2L, 9L, "written@example.com", InvitationRecordStatus.ACTIVE, future, 102L);
    ApplicationEntity a2 = app(102L, 9L, ApplicationStatus.INTERVIEW_INVITED, null, null);
    WrittenTestEntity w2 = new WrittenTestEntity();
    w2.setApplicationId(102L);
    w2.setStatus(WrittenTestStatus.IN_PROGRESS);

    // #3 已过初筛但未开考（算待参加）
    RecruitmentInvitationEntity i3 = invite(3L, 9L, "waiting@example.com", InvitationRecordStatus.ACTIVE, future, 103L);
    ApplicationEntity a3 = app(103L, 9L, ApplicationStatus.INTERVIEW_INVITED, null, null);
    a3.setInviteExpiresAt(future);

    // #4 尚未投递简历
    RecruitmentInvitationEntity i4 = invite(4L, 9L, "none@example.com", InvitationRecordStatus.ACTIVE, future, null);

    // #5 初筛未通过
    RecruitmentInvitationEntity i5 = invite(5L, 9L, "reject@example.com", InvitationRecordStatus.ACTIVE, future, 105L);
    ApplicationEntity a5 = app(105L, 9L, ApplicationStatus.SCREEN_REJECTED, null, null);
    a5.setScreeningScore(48);

    // #6 已撤销
    RecruitmentInvitationEntity i6 = invite(6L, 9L, "revoked@example.com", InvitationRecordStatus.REVOKED, future, null);

    List<RecruitmentInvitationEntity> all = List.of(i1, i2, i3, i4, i5, i6);
    when(invitationRepository.searchAll(9L, "%%")).thenReturn(all);
    when(applicationRepository.findAllById(List.of(101L, 102L, 103L, 105L)))
        .thenReturn(List.of(a1, a2, a3, a5));
    when(writtenTestRepository.findByApplicationIdIn(any())).thenReturn(List.of(w2));
    when(candidateRepository.findByEmail(any())).thenReturn(Optional.empty());

    InvitationStatsDTO stats = service.stats(9L);
    assertThat(stats.total()).isEqualTo(6);
    assertThat(stats.participated()).isEqualTo(2);
    assertThat(stats.pendingInterview()).isEqualTo(1);
    assertThat(stats.notApplied()).isEqualTo(1);
    assertThat(stats.screenRejected()).isEqualTo(1);
    assertThat(stats.expiredOrRevoked()).isEqualTo(1);

    when(jobPositionRepository.findAllById(List.of(9L))).thenReturn(List.of(job));
    var participatedPage = service.list(9L, InvitationParticipationFilter.PARTICIPATED, null, 1, 20);
    assertThat(participatedPage.total()).isEqualTo(2);
    assertThat(participatedPage.items())
        .extracting(InvitationListItemDTO::email)
        .containsExactly("done@example.com", "written@example.com");
    assertThat(participatedPage.items()).allMatch(InvitationListItemDTO::participated);
  }

  private RecruitmentInvitationEntity invite(Long id, Long jobId, String email, InvitationRecordStatus status,
                                             LocalDateTime expiresAt, Long appId) {
    RecruitmentInvitationEntity inv = new RecruitmentInvitationEntity();
    inv.setId(id);
    inv.setJobId(jobId);
    inv.setEmail(email);
    inv.setStatus(status);
    inv.setInvitedAt(LocalDateTime.now());
    inv.setExpiresAt(expiresAt);
    inv.setApplicationId(appId);
    return inv;
  }

  private ApplicationEntity app(Long id, Long jobId, ApplicationStatus status, InterviewMode mode, Integer aiScore) {
    ApplicationEntity a = new ApplicationEntity();
    a.setId(id);
    a.setJobId(jobId);
    a.setCandidateId(id + 1000);
    a.setCandidateName("候选人" + id);
    a.setStatus(status);
    a.setInterviewMode(mode);
    a.setAiScore(aiScore);
    return a;
  }
}
