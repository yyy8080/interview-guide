package interview.guide.modules.recruitment.listener;

import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.mail.model.EmailTemplateCode;
import interview.guide.modules.mail.service.IcsCalendarBuilder;
import interview.guide.modules.mail.service.MailOutboxService;
import interview.guide.modules.mail.service.MailSettingsService;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateEntity;
import interview.guide.modules.recruitment.model.CandidateScreeningFeedbackDTO;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.CandidateRepository;
import interview.guide.modules.recruitment.service.ApplicationStatusChangedEvent;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;

/**
 * 投递状态变更邮件通知。
 * <p>
 * 在状态变更事务提交后，以独立事务写入发件箱；邮件失败不会影响业务状态流转，
 * 发件箱由调度器负责重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecruitmentNotificationListener {

  static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

  /** 线下面试日历邀请序号基准（秒），保证每次改期序号递增 */
  private static final long ICS_SEQUENCE_BASE = 1_700_000_000L;

  private final ApplicationRepository applicationRepository;
  private final CandidateRepository candidateRepository;
  private final JobPositionRepository jobPositionRepository;
  private final InterviewScheduleRepository scheduleRepository;
  private final MailOutboxService mailOutboxService;
  private final MailSettingsService mailSettingsService;
  private final TransactionalExecutor transactionalExecutor;
  private final ObjectMapper objectMapper;

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onStatusChanged(ApplicationStatusChangedEvent event) {
    try {
      transactionalExecutor.runRequiresNew(() -> notify(event));
    } catch (Exception e) {
      log.error("写入投递通知邮件失败: applicationId={}, to={}, error={}",
          event.applicationId(), event.to(), e.getMessage());
    }
  }

  void notify(ApplicationStatusChangedEvent event) {
    EmailTemplateCode code = templateFor(event.to());
    if (code == null) {
      return;
    }
    ApplicationEntity app = applicationRepository.findById(event.applicationId()).orElse(null);
    if (app == null) {
      return;
    }
    CandidateEntity candidate = candidateRepository.findById(app.getCandidateId()).orElse(null);
    if (candidate == null || candidate.getEmail() == null) {
      return;
    }
    JobPositionEntity job = jobPositionRepository.findById(app.getJobId()).orElse(null);
    // 未开启初筛时会直接收到面试邀请，不再单独发送投递成功通知
    if (code == EmailTemplateCode.APPLICATION_RECEIVED && job != null && !job.isScreeningEnabled()) {
      return;
    }

    Map<String, String> vars = new HashMap<>();
    vars.put("candidateName", app.getCandidateName());
    vars.put("jobTitle", job != null ? job.getTitle() : "");
    String ics = null;
    switch (code) {
      case INTERVIEW_INVITE -> {
        vars.put("expireAt", app.getInviteExpiresAt() != null ? TIME_FORMAT.format(app.getInviteExpiresAt()) : "尽快");
        vars.put("interviewMode", modeLabel(job != null ? job.getInterviewMode() : null));
        vars.put("timeLimit", timeLimitText(job));
      }
      case REJECTED -> vars.put("rejectReason", buildRejectReason(event.to(), app));
      case OFFLINE_INVITE -> {
        InterviewScheduleEntity schedule = scheduleRepository.findByApplicationId(app.getId()).orElse(null);
        if (schedule == null) {
          log.warn("线下面试安排不存在，跳过邀约邮件: applicationId={}", app.getId());
          return;
        }
        vars.put("interviewTime", TIME_FORMAT.format(schedule.getInterviewTime()));
        vars.put("durationMinutes", String.valueOf(durationOf(schedule)));
        vars.put("location", nullToDash(schedule.getLocationAddress()));
        vars.put("interviewer", nullToDash(schedule.getInterviewer()));
        vars.put("notes", nullToDash(schedule.getNotes()));
        ics = buildIcs(app, job, schedule);
      }
      default -> {
        // 其他模板只需要公共变量
      }
    }
    mailOutboxService.enqueue(app.getId(), candidate.getEmail(), code, vars, ics);
  }

  private String buildRejectReason(ApplicationStatus status, ApplicationEntity app) {
    if (status != ApplicationStatus.SCREEN_REJECTED) {
      return "";
    }
    CandidateScreeningFeedbackDTO feedback = CandidateScreeningFeedbackDTO.fromJson(
        app.getScreeningResultJson(), objectMapper);
    StringBuilder sb = new StringBuilder("\n\n简历初筛未通过原因：").append(feedback.summary());
    if (!feedback.missingRequirements().isEmpty()) {
      sb.append("\n与岗位要求的主要差距：").append(String.join("；", feedback.missingRequirements()));
    }
    return sb.toString();
  }

  static EmailTemplateCode templateFor(ApplicationStatus status) {
    if (status == null) {
      return null;
    }
    return switch (status) {
      case SUBMITTED -> EmailTemplateCode.APPLICATION_RECEIVED;
      case INTERVIEW_INVITED -> EmailTemplateCode.INTERVIEW_INVITE;
      case SCREEN_REJECTED, AI_REJECTED, HR_REJECTED -> EmailTemplateCode.REJECTED;
      case OFFLINE_SCHEDULED -> EmailTemplateCode.OFFLINE_INVITE;
      default -> null;
    };
  }

  private String buildIcs(ApplicationEntity app, JobPositionEntity job, InterviewScheduleEntity schedule) {
    ResolvedMailConfig config = mailSettingsService.resolve();
    int sequence = (int) Math.max(0, System.currentTimeMillis() / 1000 - ICS_SEQUENCE_BASE);
    String title = (job != null ? job.getTitle() : "") + " 线下面试";
    StringBuilder description = new StringBuilder();
    description.append(config.companyName()).append(" 线下面试");
    if (schedule.getInterviewer() != null) {
      description.append("\n面试官：").append(schedule.getInterviewer());
    }
    if (schedule.getNotes() != null) {
      description.append("\n备注：").append(schedule.getNotes());
    }
    return IcsCalendarBuilder.build("application-" + app.getId() + "@interview-guide", sequence,
        schedule.getInterviewTime(), durationOf(schedule), title, schedule.getLocationAddress(),
        description.toString(), config.fromAddress(), config.fromName(), ZoneId.systemDefault());
  }

  static String modeLabel(InterviewMode mode) {
    if (mode == null) {
      return "文字面试";
    }
    return switch (mode) {
      case TEXT -> "文字面试";
      case VOICE -> "语音面试";
      case BOTH -> "文字或语音面试（开始时自选）";
    };
  }

  static String timeLimitText(JobPositionEntity job) {
    if (job == null) {
      return "";
    }
    String count = job.getQuestionCount() != null ? "约 " + job.getQuestionCount() + " 道题，" : "";
    String limit = job.getTimeLimitMinutes() != null && job.getTimeLimitMinutes() > 0
        ? "限时 " + job.getTimeLimitMinutes() + " 分钟" : "不限作答时长";
    return count + limit;
  }

  private static int durationOf(InterviewScheduleEntity schedule) {
    return schedule.getDurationMinutes() != null ? schedule.getDurationMinutes() : 60;
  }

  private static String nullToDash(String value) {
    return value == null || value.isBlank() ? "—" : value;
  }

  static String format(LocalDateTime time) {
    return time == null ? "" : TIME_FORMAT.format(time);
  }
}
