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
        new ObjectMapper());
  }

  @Test
  @DisplayName("默认简历初筛分数线下调为 45 分（宽进严出）")
  void defaultScreeningThresholdIsLoweredToFortyFive() {
    RecruitmentProperties props = new RecruitmentProperties();
    assertThat(props.getDefaultScreeningThreshold()).isEqualTo(45);
  }

  @Test
  @DisplayName("简历初筛未通过通知邮件附带具体原因与主要差距，而 HR 拒绝邮件不附带内部评语")
  @SuppressWarnings("unchecked")
  void includesScreeningRejectReasonOnlyWhenScreenRejected() {
    JobPositionEntity job = new JobPositionEntity();
    job.setId(10L);
    job.setTitle("Java 研发工程师");
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
    screenRejected.setScreeningResultJson("""
        {
          "score": 32,
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
        .contains("Java 基础与 Web 开发项目经历；SQL 数据库基础");

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
