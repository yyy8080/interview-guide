package interview.guide.modules.recruitment.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateApplicationDTO;
import interview.guide.modules.recruitment.model.CandidateEntity;
import interview.guide.modules.recruitment.model.CandidateLevels;
import interview.guide.modules.recruitment.model.CandidateOfflineDTO;
import interview.guide.modules.recruitment.model.OperatorType;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.CandidateRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.resume.service.ResumeUploadService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 候选人侧投递：投递简历、查看进度、确认 / 申请改期线下面试
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CandidateApplicationService {

  public static final String CONFIRM_PENDING = "PENDING";
  public static final String CONFIRM_CONFIRMED = "CONFIRMED";
  public static final String CONFIRM_RESCHEDULE = "RESCHEDULE_REQUESTED";

  private final ApplicationRepository applicationRepository;
  private final CandidateRepository candidateRepository;
  private final JobPositionRepository jobPositionRepository;
  private final InterviewScheduleRepository scheduleRepository;
  private final ResumeUploadService resumeUploadService;
  private final ResumeRepository resumeRepository;
  private final ApplicationStateMachine stateMachine;
  private final ApplicationWorkflowService workflowService;
  private final TransactionalExecutor transactionalExecutor;

  /**
   * 投递岗位：校验 → 上传解析简历（事务外）→ 事务内创建投递单并进入初筛 / 面试邀请
   */
  public CandidateApplicationDTO apply(Long jobId, Long candidateId, String name, String phone, boolean consent,
                                       MultipartFile file) {
    return apply(jobId, candidateId, name, phone, consent, file, null);
  }

  /**
   * 投递岗位：校验 → 上传解析简历（事务外）→ 事务内创建投递单并进入初筛 / 面试邀请
   */
  public CandidateApplicationDTO apply(Long jobId, Long candidateId, String name, String phone, boolean consent,
                                       MultipartFile file, String candidateLevel) {
    if (!consent) {
      throw new BusinessException(ErrorCode.CONSENT_REQUIRED);
    }
    String normalizedLevel = CandidateLevels.normalizeOrNull(candidateLevel);
    String candidateName = trimToNull(name);
    if (candidateName == null || candidateName.length() > 64) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写姓名（不超过 64 字）");
    }
    String candidatePhone = trimToNull(phone);
    if (candidatePhone != null && !candidatePhone.matches("^[0-9+\\-() ]{5,32}$")) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "手机号格式不正确");
    }
    JobPositionEntity job = jobPositionRepository.findById(jobId)
        .orElseThrow(() -> new BusinessException(ErrorCode.JOB_NOT_FOUND));
    if (!job.isAcceptingApplications(LocalDateTime.now())) {
      throw new BusinessException(ErrorCode.JOB_NOT_ACCEPTING);
    }
    candidateRepository.findById(candidateId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CANDIDATE_UNAUTHORIZED));
    if (applicationRepository.existsByJobIdAndCandidateId(jobId, candidateId)) {
      throw new BusinessException(ErrorCode.APPLICATION_DUPLICATE);
    }

    String resolvedLevel = CandidateLevels.resolve(normalizedLevel, job.getDifficulty());
    ResumeEntity resume = resumeUploadService.uploadForApplication(file);

    ApplicationEntity saved;
    try {
      saved = transactionalExecutor.call(() -> createApplication(job, candidateId, candidateName, candidatePhone,
          resolvedLevel, resume.getId()));
    } catch (DataIntegrityViolationException e) {
      discardResume(resume);
      throw new BusinessException(ErrorCode.APPLICATION_DUPLICATE);
    } catch (RuntimeException e) {
      discardResume(resume);
      throw e;
    }
    log.info("候选人投递成功: applicationId={}, jobId={}, candidateId={}, level={}, status={}",
        saved.getId(), jobId, candidateId, resolvedLevel, saved.getStatus());
    return toDTO(saved, job, null);
  }

  /** 投递失败时清理已上传的简历（文件 + 记录） */
  private void discardResume(ResumeEntity resume) {
    resumeUploadService.deleteStoredQuietly(resume.getStorageKey());
    try {
      resumeRepository.deleteById(resume.getId());
    } catch (Exception e) {
      log.warn("清理投递失败的简历记录失败: resumeId={}", resume.getId());
    }
  }

  private ApplicationEntity createApplication(JobPositionEntity job, Long candidateId, String name, String phone,
                                              String candidateLevel, Long resumeId) {
    LocalDateTime now = LocalDateTime.now();
    ApplicationEntity application = new ApplicationEntity();
    application.setJobId(job.getId());
    application.setCandidateId(candidateId);
    application.setResumeId(resumeId);
    application.setCandidateName(name);
    application.setCandidatePhone(phone);
    application.setCandidateLevel(candidateLevel);
    application.setStatus(ApplicationStatus.SUBMITTED);
    application.setConsentAt(now);
    application.setSubmittedAt(now);
    ApplicationEntity saved = applicationRepository.saveAndFlush(application);
    stateMachine.initialize(saved, "候选人投递简历（自选定位：" + CandidateLevels.label(candidateLevel) + "）");

    // 同步候选人资料（最近一次投递填写的信息）
    candidateRepository.findById(candidateId).ifPresent(candidate -> updateProfile(candidate, name, phone));

    if (job.isScreeningEnabled()) {
      saved.setTaskUpdatedAt(now);
      stateMachine.transit(saved, ApplicationStatus.SCREENING, OperatorType.SYSTEM, null, "进入 AI 简历初筛");
    } else {
      workflowService.invite(saved, OperatorType.SYSTEM, null, "岗位未开启初筛，直接进入在线面试");
    }
    return saved;
  }

  private void updateProfile(CandidateEntity candidate, String name, String phone) {
    candidate.setName(name);
    if (phone != null) {
      candidate.setPhone(phone);
    }
    candidateRepository.save(candidate);
  }

  @Transactional(readOnly = true)
  public List<CandidateApplicationDTO> listMine(Long candidateId) {
    List<ApplicationEntity> applications = applicationRepository.findByCandidateIdOrderBySubmittedAtDesc(candidateId);
    if (applications.isEmpty()) {
      return List.of();
    }
    Map<Long, JobPositionEntity> jobs = jobPositionRepository.findAllById(
            applications.stream().map(ApplicationEntity::getJobId).distinct().toList())
        .stream().collect(Collectors.toMap(JobPositionEntity::getId, Function.identity()));
    return applications.stream()
        .map(app -> toDTO(app, jobs.get(app.getJobId()), offlineOf(app)))
        .toList();
  }

  @Transactional(readOnly = true)
  public CandidateApplicationDTO getMine(Long candidateId, Long applicationId) {
    ApplicationEntity application = requireOwned(candidateId, applicationId);
    JobPositionEntity job = jobPositionRepository.findById(application.getJobId()).orElse(null);
    return toDTO(application, job, offlineOf(application));
  }

  /** 候选人确认参加线下面试 */
  @Transactional(rollbackFor = Exception.class)
  public CandidateApplicationDTO confirmOffline(Long candidateId, Long applicationId) {
    ApplicationEntity application = applicationRepository.findByIdForUpdate(applicationId)
        .filter(app -> app.getCandidateId().equals(candidateId))
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
    if (application.getStatus() != ApplicationStatus.OFFLINE_SCHEDULED) {
      throw new BusinessException(ErrorCode.APPLICATION_STATUS_INVALID, "当前没有待确认的线下面试");
    }
    InterviewScheduleEntity schedule = scheduleRepository.findByApplicationId(applicationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_STATUS_INVALID, "线下面试安排不存在"));
    schedule.setCandidateConfirmStatus(CONFIRM_CONFIRMED);
    schedule.setConfirmedAt(LocalDateTime.now());
    scheduleRepository.save(schedule);
    stateMachine.transit(application, ApplicationStatus.OFFLINE_CONFIRMED, OperatorType.CANDIDATE, candidateId,
        "候选人确认参加线下面试");
    JobPositionEntity job = jobPositionRepository.findById(application.getJobId()).orElse(null);
    return toDTO(application, job, toOffline(schedule));
  }

  /** 候选人申请改期：记录原因，等待 HR 重新安排（状态保持不变） */
  @Transactional(rollbackFor = Exception.class)
  public CandidateApplicationDTO requestReschedule(Long candidateId, Long applicationId, String note) {
    ApplicationEntity application = applicationRepository.findByIdForUpdate(applicationId)
        .filter(app -> app.getCandidateId().equals(candidateId))
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
    if (application.getStatus() != ApplicationStatus.OFFLINE_SCHEDULED
        && application.getStatus() != ApplicationStatus.OFFLINE_CONFIRMED) {
      throw new BusinessException(ErrorCode.APPLICATION_STATUS_INVALID, "当前没有可改期的线下面试");
    }
    InterviewScheduleEntity schedule = scheduleRepository.findByApplicationId(applicationId)
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_STATUS_INVALID, "线下面试安排不存在"));
    schedule.setCandidateConfirmStatus(CONFIRM_RESCHEDULE);
    schedule.setCandidateNote(note.trim());
    schedule.setConfirmedAt(null);
    scheduleRepository.save(schedule);
    stateMachine.log(application, OperatorType.CANDIDATE, candidateId, "候选人申请改期：" + note.trim());
    log.info("候选人申请线下面试改期: applicationId={}", applicationId);
    JobPositionEntity job = jobPositionRepository.findById(application.getJobId()).orElse(null);
    return toDTO(application, job, toOffline(schedule));
  }

  ApplicationEntity requireOwned(Long candidateId, Long applicationId) {
    return applicationRepository.findByIdAndCandidateId(applicationId, candidateId)
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
  }

  private CandidateOfflineDTO offlineOf(ApplicationEntity application) {
    if (application.getStatus() != ApplicationStatus.OFFLINE_SCHEDULED
        && application.getStatus() != ApplicationStatus.OFFLINE_CONFIRMED) {
      return null;
    }
    return scheduleRepository.findByApplicationId(application.getId()).map(this::toOffline).orElse(null);
  }

  private CandidateOfflineDTO toOffline(InterviewScheduleEntity schedule) {
    return new CandidateOfflineDTO(schedule.getInterviewTime(), schedule.getDurationMinutes(),
        schedule.getLocationAddress(), schedule.getInterviewer(), schedule.getNotes(),
        schedule.getCandidateConfirmStatus(), schedule.getCandidateNote());
  }

  static List<InterviewMode> availableModes(JobPositionEntity job) {
    if (job == null || job.getInterviewMode() == null) {
      return List.of(InterviewMode.TEXT);
    }
    return Arrays.stream(new InterviewMode[] {InterviewMode.TEXT, InterviewMode.VOICE})
        .filter(mode -> job.getInterviewMode().allows(mode))
        .toList();
  }

  static CandidateApplicationDTO toDTO(ApplicationEntity app, JobPositionEntity job, CandidateOfflineDTO offline) {
    return new CandidateApplicationDTO(
        app.getId(),
        app.getJobId(),
        job != null ? job.getTitle() : "（岗位已删除）",
        job != null ? job.getDepartment() : null,
        job != null ? job.getLocation() : null,
        app.getStatus().toCandidateStage(),
        interview.guide.modules.recruitment.model.CandidateLevels.resolve(
            app.getCandidateLevel(), job != null ? job.getDifficulty() : null),
        availableModes(job),
        app.getInterviewMode(),
        job != null ? job.getQuestionCount() : null,
        job != null ? job.getTimeLimitMinutes() : null,
        app.getSubmittedAt(),
        app.getInviteExpiresAt(),
        app.getInterviewStartedAt(),
        app.getInterviewDeadlineAt(),
        app.getInterviewCompletedAt(),
        offline);
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
