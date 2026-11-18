package interview.guide.modules.recruitment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.mail.model.EmailTemplateCode;
import interview.guide.modules.mail.service.MailOutboxService;
import interview.guide.modules.mail.service.MailSettingsService;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateEntity;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.CandidateRepository;
import interview.guide.modules.recruitment.service.ApplicationStatusChangedEvent;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("投递通知邮件与默认初筛门槛测试")
class RecruitmentNotificationListenerTest {

  @Mock
  private ApplicationRepository applicationRepository;

  @Mock
  private CandidateRepository candidateRepository;

  @Mock
  private JobPositionRepository jobPositionRepository;

  @Mock
  private InterviewScheduleRepository scheduleRepository;

  @Mock
  private MailOutboxService mailOutboxService;

  @Mock
  private MailSettingsService mailSettingsService;

  @Mock
  private TransactionalExecutor transactionalExecutor;

  private RecruitmentNotificationListener listener;

  @BeforeEach
  void setUp() {
    listener = new RecruitmentNotificationListener(
        applicationRepository,
        candidateRepository,
        jobPositionRepository,
        scheduleRepository,
        mailOutboxService,
        mailSettingsService,
        transactionalExecutor,
        new ObjectMapper(),
        new RecruitmentProperties());
  }

  @Test
  @DisplayName("默认简历初筛分数线为 60 分，且每份投递默认提供 1 次简历修改机会")
  void defaultScreeningThresholdIsSixtyWithOneResumeRevision() {
    RecruitmentProperties props = new RecruitmentProperties();
    assertThat(props.getDefaultScreeningThreshold()).isEqualTo(60);
    assertThat(props.getMaxResumeRevisions()).isEqualTo(1);
  }

  @Test
  @DisplayName("首次简历初筛未通过附带原因与 1 次修改重提提示，用完机会后不再提示重提，HR 拒绝不附带内部评语")
  @SuppressWarnings("unchecked")
  void includesScreeningRejectReasonAndRevisionNoticeAppropriately() {
    JobPositionEntity job = new JobPositionEntity();
    job.setId(10L);
    job.setTitle("Java 研发工程师");
    job.setStatus(interview.guide.modules.job.model.JobStatus.OPEN);
    job.setScreeningEnabled(true);

    CandidateEntity candidate = new CandidateEntity();
    candidate.setId(88L);
    candidate.setEmail("seeker@example.com");

    ApplicationEntity screenRejected = new ApplicationEntity();
    screenRejected.setId(501L);
    screenRejected.setJobId(10L);
    screenRejected.setCandidateId(88L);
    screenRejected.setCandidateName("陈同学");
    screenRejected.setStatus(ApplicationStatus.SCREEN_REJECTED);
    screenRejected.setResumeRevisionCount(0);
    screenRejected.setScreeningResultJson("""
        {
          "score": 48,
          "summary": "简历暂未体现后端服务端开发经验，与岗位技术方向存在差距。",
          "missingRequirements": ["Java 基础与 Web 开发项目经历", "SQL 数据库基础"]
        }
        """);

    when(applicationRepository.findById(501L)).thenReturn(Optional.of(screenRejected));
    when(candidateRepository.findById(88L)).thenReturn(Optional.of(candidate));
    when(jobPositionRepository.findById(10L)).thenReturn(Optional.of(job));

    listener.notify(new ApplicationStatusChangedEvent(501L, ApplicationStatus.SCREENING, ApplicationStatus.SCREEN_REJECTED));

    ArgumentCaptor<Map<String, String>> varsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mailOutboxService).enqueue(eq(501L), eq("seeker@example.com"),
        eq(EmailTemplateCode.REJECTED), varsCaptor.capture(), isNull());
    assertThat(varsCaptor.getValue().get("rejectReason"))
        .contains("简历初筛未通过原因：简历暂未体现后端服务端开发经验，与岗位技术方向存在差距。")
        .contains("Java 基础与 Web 开发项目经历；SQL 数据库基础")
        .contains("1 次补充完善并重新上传简历的机会")
        .contains("后续语音面试将围绕您简历中的内容核验真实性");

    ApplicationEntity secondScreenRejected = new ApplicationEntity();
    secondScreenRejected.setId(503L);
    secondScreenRejected.setJobId(10L);
    secondScreenRejected.setCandidateId(88L);
    secondScreenRejected.setCandidateName("陈同学");
    secondScreenRejected.setStatus(ApplicationStatus.SCREEN_REJECTED);
    secondScreenRejected.setResumeRevisionCount(1);
    secondScreenRejected.setScreeningResultJson("""
        {"score": 52, "summary": "补充后项目技术栈仍与后端岗位差距较大。", "missingRequirements": ["Spring Boot 服务端开发"]}
        """);
    when(applicationRepository.findById(503L)).thenReturn(Optional.of(secondScreenRejected));

    listener.notify(new ApplicationStatusChangedEvent(503L, ApplicationStatus.SCREENING, ApplicationStatus.SCREEN_REJECTED));

    ArgumentCaptor<Map<String, String>> secondVarsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mailOutboxService).enqueue(eq(503L), eq("seeker@example.com"),
        eq(EmailTemplateCode.REJECTED), secondVarsCaptor.capture(), isNull());
    assertThat(secondVarsCaptor.getValue().get("rejectReason"))
        .contains("补充后项目技术栈仍与后端岗位差距较大。")
        .doesNotContain("补充完善并重新上传简历的机会");

    ApplicationEntity hrRejected = new ApplicationEntity();
    hrRejected.setId(502L);
    hrRejected.setJobId(10L);
    hrRejected.setCandidateId(88L);
    hrRejected.setCandidateName("陈同学");
    hrRejected.setStatus(ApplicationStatus.HR_REJECTED);
    hrRejected.setHrComment("内部评语不应外发");
    when(applicationRepository.findById(502L)).thenReturn(Optional.of(hrRejected));

    listener.notify(new ApplicationStatusChangedEvent(502L, ApplicationStatus.AI_PASSED, ApplicationStatus.HR_REJECTED));

    ArgumentCaptor<Map<String, String>> hrVarsCaptor = ArgumentCaptor.forClass(Map.class);
    verify(mailOutboxService).enqueue(eq(502L), eq("seeker@example.com"),
        eq(EmailTemplateCode.REJECTED), hrVarsCaptor.capture(), isNull());
    assertThat(hrVarsCaptor.getValue().get("rejectReason")).isEmpty();
  }
}
