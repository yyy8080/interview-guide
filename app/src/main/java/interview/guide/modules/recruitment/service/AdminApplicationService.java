package interview.guide.modules.recruitment.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.PageResult;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.auth.repository.AdminUserRepository;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.mail.service.MailOutboxService;
import interview.guide.modules.mail.service.MailSettingsService;
import interview.guide.modules.recruitment.model.AiReviewRecord;
import interview.guide.modules.recruitment.model.AnswerTelemetryDTO;
import interview.guide.modules.recruitment.model.AnswerTelemetryEntity;
import interview.guide.modules.recruitment.model.ApplicationDetailDTO;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationListItemDTO;
import interview.guide.modules.recruitment.model.ApplicationStatsDTO;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.ApplicationStatusLogDTO;
import interview.guide.modules.recruitment.model.BatchReviewRequest;
import interview.guide.modules.recruitment.model.BatchReviewResult;
import interview.guide.modules.recruitment.model.CandidateEntity;
import interview.guide.modules.recruitment.model.OfflineInterviewDTO;
import interview.guide.modules.recruitment.model.OfflineInterviewRequest;
import interview.guide.modules.recruitment.model.OperatorType;
import interview.guide.modules.recruitment.model.ProctorEventDTO;
import interview.guide.modules.recruitment.model.ProctorEventEntity;
import interview.guide.modules.recruitment.model.ProctorEventType;
import interview.guide.modules.recruitment.model.ProctorRiskAssessment;
import interview.guide.modules.recruitment.model.ProctorRiskLevel;
import interview.guide.modules.recruitment.model.ScreeningResult;
import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionResult;
import interview.guide.modules.recruitment.model.WrittenTestDetailDTO;
import interview.guide.modules.recruitment.model.WrittenTestEntity;
import interview.guide.modules.recruitment.model.WrittenTestItemDTO;
import interview.guide.modules.recruitment.repository.AnswerTelemetryRepository;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.ApplicationStatusLogRepository;
import interview.guide.modules.recruitment.repository.CandidateRepository;
import interview.guide.modules.recruitment.repository.ProctorEventRepository;
import interview.guide.modules.recruitment.repository.WrittenTestRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 后台投递管理：列表筛选、审核工作台、HR 审核（单条 / 批量）、线下面试安排、招聘漏斗统计
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminApplicationService {

  private static final int MAX_PAGE_SIZE = 100;

  private final ApplicationRepository applicationRepository;
  private final CandidateRepository candidateRepository;
  private final JobPositionRepository jobPositionRepository;
  private final ResumeRepository resumeRepository;
  private final ProctorEventRepository proctorEventRepository;
  private final AnswerTelemetryRepository answerTelemetryRepository;
  private final WrittenTestRepository writtenTestRepository;
  private final WrittenTestCodec writtenTestCodec;
  private final ProctorRiskService proctorRiskService;
  private final ApplicationStatusLogRepository statusLogRepository;
  private final InterviewScheduleRepository scheduleRepository;
  private final AdminUserRepository adminUserRepository;
  private final ApplicationStateMachine stateMachine;
  private final MailOutboxService mailOutboxService;
  private final MailSettingsService mailSettingsService;
  private final FileStorageService fileStorageService;
  private final TransactionalExecutor transactionalExecutor;
  private final ObjectMapper objectMapper;

  /** 简历原文件 */
  public record ResumeFile(String filename, String contentType, byte[] content) {
  }

  // ========== 列表 / 详情 ==========

  @Transactional(readOnly = true)
  public PageResult<ApplicationListItemDTO> list(Long jobId, Collection<ApplicationStatus> statuses, String keyword,
                                                 int page, int size) {
    return list(jobId, statuses, null, keyword, page, size);
  }

  @Transactional(readOnly = true)
  public PageResult<ApplicationListItemDTO> list(Long jobId, Collection<ApplicationStatus> statuses,
                                                 Collection<ProctorRiskLevel> riskLevels, String keyword,
                                                 int page, int size) {
    boolean allRiskLevels = riskLevels == null || riskLevels.isEmpty();
    Collection<ProctorRiskLevel> riskFilter = allRiskLevels ? EnumSet.allOf(ProctorRiskLevel.class) : riskLevels;
    boolean allStatuses = statuses == null || statuses.isEmpty();
    Collection<ApplicationStatus> filter = allStatuses ? EnumSet.allOf(ApplicationStatus.class) : statuses;
    String pattern = "%" + (keyword == null ? "" : keyword.trim().toLowerCase()) + "%";
    PageRequest pageable = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
        Sort.by(Sort.Direction.DESC, "submittedAt").and(Sort.by(Sort.Direction.DESC, "id")));
    Page<ApplicationEntity> result = applicationRepository.search(jobId, allStatuses, filter, allRiskLevels,
        riskFilter, pattern, pageable);

    Map<Long, CandidateEntity> candidates = candidateRepository.findAllById(
            result.getContent().stream().map(ApplicationEntity::getCandidateId).distinct().toList())
        .stream().collect(Collectors.toMap(CandidateEntity::getId, Function.identity()));
    Map<Long, JobPositionEntity> jobs = jobPositionRepository.findAllById(
            result.getContent().stream().map(ApplicationEntity::getJobId).distinct().toList())
        .stream().collect(Collectors.toMap(JobPositionEntity::getId, Function.identity()));

    return PageResult.of(result, app -> {
      CandidateEntity candidate = candidates.get(app.getCandidateId());
      JobPositionEntity job = jobs.get(app.getJobId());
      return new ApplicationListItemDTO(
          app.getId(), app.getJobId(), job != null ? job.getTitle() : "（岗位已删除）",
          app.getCandidateName(), candidate != null ? candidate.getEmail() : null, app.getCandidatePhone(),
          interview.guide.modules.recruitment.model.CandidateLevels.resolve(
              app.getCandidateLevel(), job != null ? job.getDifficulty() : null),
          app.getStatus(), app.getInterviewMode(), app.getScreeningScore(), app.getWrittenScore(), app.getAiScore(),
          app.getAiDecision(), app.getProctorRiskScore(), app.getProctorRiskLevel(),
          app.getSubmittedAt(), app.getUpdatedAt());
    });
  }

  @Transactional(readOnly = true)
  public ApplicationDetailDTO detail(Long id) {
    ApplicationEntity app = require(id);
    CandidateEntity candidate = candidateRepository.findById(app.getCandidateId()).orElse(null);
    JobPositionEntity job = jobPositionRepository.findById(app.getJobId()).orElse(null);
    ResumeEntity resume = resumeRepository.findById(app.getResumeId()).orElse(null);

    Map<ProctorEventType, Long> proctorCounts = new EnumMap<>(ProctorEventType.class);
    proctorEventRepository.countByType(id).forEach(c -> proctorCounts.put(c.getEventType(), c.getTotal()));
    List<ProctorEventEntity> eventEntities = proctorEventRepository.findByApplicationIdOrderByOccurredAtAsc(id);
    List<ProctorEventDTO> proctorEvents = eventEntities.stream()
        .map(e -> new ProctorEventDTO(e.getId(), e.getEventType(), e.getDetail(), e.getDurationMs(),
            e.getQuestionIndex(), e.getChars(), e.getOccurredAt()))
        .toList();
    List<AnswerTelemetryEntity> telemetryEntities = answerTelemetryRepository.findByApplicationIdOrderByQuestionIndexAsc(id);
    // 实时计算（面试进行中也可查看）；列表展示使用交卷时落库的结果
    ProctorRiskAssessment proctorRisk = proctorRiskService.assess(eventEntities, telemetryEntities);
    Set<Integer> flaggedQuestions = new HashSet<>(proctorRisk.flaggedQuestions());
    List<AnswerTelemetryDTO> answerTelemetry = telemetryEntities.stream()
        .map(t -> new AnswerTelemetryDTO(t.getQuestionIndex(), t.getDurationMs(), t.getFirstInputMs(),
            t.getTypedChars(), t.getPastedChars(), t.getMaxBurstChars(), t.getAwayMs(), t.getAnswerChars(),
            ProctorRiskCalculator.pasteRatio(t.getTypedChars(), t.getPastedChars()),
            flaggedQuestions.contains(t.getQuestionIndex())))
        .toList();

    var logEntities = statusLogRepository.findByApplicationIdOrderByCreatedAtAscIdAsc(id);
    Set<Long> adminIds = new HashSet<>();
    logEntities.stream().filter(l -> l.getOperatorType() == OperatorType.HR && l.getOperatorId() != null)
        .forEach(l -> adminIds.add(l.getOperatorId()));
    if (app.getHrReviewerId() != null) {
      adminIds.add(app.getHrReviewerId());
    }
    Map<Long, String> adminNames = adminUserRepository.findAllById(adminIds).stream()
        .collect(Collectors.toMap(AdminUserEntity::getId, AdminApplicationService::displayName));
    List<ApplicationStatusLogDTO> logs = logEntities.stream()
        .map(l -> new ApplicationStatusLogDTO(l.getId(), l.getFromStatus(), l.getToStatus(), l.getOperatorType(),
            l.getOperatorId(), operatorName(l.getOperatorType(), l.getOperatorId(), adminNames), l.getRemark(),
            l.getCreatedAt()))
        .toList();

    OfflineInterviewDTO offline = scheduleRepository.findByApplicationId(id).map(AdminApplicationService::toOffline)
        .orElse(null);

    AiReviewRecord aiReview = parse(app.getAiResultJson(), AiReviewRecord.class);
    WrittenTestDetailDTO writtenDetail = writtenTestRepository.findByApplicationId(id)
        .map(test -> toWrittenDetail(test, job, aiReview))
        .orElse(null);

    return new ApplicationDetailDTO(
        app.getId(), app.getStatus(),
        app.getCandidateId(), app.getCandidateName(), candidate != null ? candidate.getEmail() : null,
        app.getCandidatePhone(),
        interview.guide.modules.recruitment.model.CandidateLevels.resolve(
            app.getCandidateLevel(), job != null ? job.getDifficulty() : null),
        app.getJobId(), job != null ? job.getTitle() : "（岗位已删除）",
        job != null ? job.getDifficulty() : null,
        job != null ? job.getAiPassThreshold() : null, job != null ? job.getScreeningThreshold() : null,
        app.getResumeId(), resume != null ? resume.getOriginalFilename() : null,
        resume != null ? resume.getResumeText() : null,
        app.getScreeningScore(), parse(app.getScreeningResultJson(), ScreeningResult.class),
        writtenDetail,
        app.getInterviewMode(), app.getInterviewSessionId(), app.getVoiceSessionId(),
        app.getInterviewStartedAt(), app.getInterviewCompletedAt(),
        app.getAiScore(), app.getAiDecision(), aiReview, app.getTaskError(),
        app.getHrReviewerId(), app.getHrReviewerId() != null ? adminNames.get(app.getHrReviewerId()) : null,
        app.getHrComment(), app.getHrReviewedAt(),
        proctorCounts, proctorEvents, proctorRisk, answerTelemetry, logs, offline,
        mailOutboxService.listByApplication(id),
        app.getSubmittedAt(), app.getInviteExpiresAt(), app.getUpdatedAt());
  }

  private WrittenTestDetailDTO toWrittenDetail(WrittenTestEntity test, JobPositionEntity job, AiReviewRecord aiReview) {
    List<WrittenQuestion> questions = writtenTestCodec.readQuestions(test.getQuestionsJson());
    Map<Integer, List<String>> answers = writtenTestCodec.readAnswers(test.getAnswersJson());
    Map<Integer, WrittenQuestionResult> results = writtenTestCodec.readResults(test.getResultsJson()).stream()
        .collect(Collectors.toMap(WrittenQuestionResult::index, Function.identity(), (a, b) -> b));
    List<WrittenTestItemDTO> items = questions.stream().map(q -> {
      WrittenQuestionResult r = results.get(q.index());
      List<String> candidateAnswer = r != null && r.answer() != null
          ? r.answer() : answers.getOrDefault(q.index(), List.of());
      return new WrittenTestItemDTO(q.index(), q.type(), q.question(), q.options(), q.answers(),
          q.explanation(), q.category(), candidateAnswer, r != null ? r.correct() : null,
          r != null ? r.judgedBy() : null, r != null ? r.reason() : null);
    }).toList();
    int weight = aiReview != null && aiReview.written() != null
        ? aiReview.written().weight()
        : (job != null ? job.getWrittenWeight() : 30);
    return new WrittenTestDetailDTO(test.getStatus(), test.getScore(), test.getCorrectCount(),
        test.getTotalCount(), weight, test.getStartedAt(), test.getDeadlineAt(), test.getSubmittedAt(),
        test.getGradedAt(), items);
  }

  @Transactional(readOnly = true)
  public ResumeFile resumeFile(Long id) {
    ApplicationEntity app = require(id);
    ResumeEntity resume = resumeRepository.findById(app.getResumeId())
        .orElseThrow(() -> new BusinessException(ErrorCode.RESUME_NOT_FOUND));
    if (resume.getStorageKey() == null) {
      throw new BusinessException(ErrorCode.RESUME_NOT_FOUND, "简历原文件不存在");
    }
    byte[] content = fileStorageService.downloadFile(resume.getStorageKey());
    String filename = resume.getOriginalFilename() != null ? resume.getOriginalFilename() : "resume-" + id;
    return new ResumeFile(filename, resume.getContentType(), content);
  }

  // ========== HR 审核 ==========

  @Transactional(rollbackFor = Exception.class)
  public ApplicationDetailDTO approve(Long id, Long operatorId, String comment) {
    review(id, true, operatorId, comment);
    return detail(id);
  }

  @Transactional(rollbackFor = Exception.class)
  public ApplicationDetailDTO reject(Long id, Long operatorId, String comment) {
    review(id, false, operatorId, comment);
    return detail(id);
  }

  /**
   * 批量审核：逐条在独立事务中执行，单条失败不影响其他
   */
  public BatchReviewResult batchReview(BatchReviewRequest request, Long operatorId) {
    List<Long> succeeded = new ArrayList<>();
    List<BatchReviewResult.Failure> failed = new ArrayList<>();
    for (Long id : request.ids().stream().filter(Objects::nonNull).distinct().toList()) {
      try {
        transactionalExecutor.run(() -> review(id, request.approve(), operatorId, request.comment()));
        succeeded.add(id);
      } catch (BusinessException e) {
        failed.add(new BatchReviewResult.Failure(id, e.getMessage()));
      } catch (Exception e) {
        log.error("批量审核失败: applicationId={}", id, e);
        failed.add(new BatchReviewResult.Failure(id, "处理失败"));
      }
    }
    return new BatchReviewResult(succeeded, failed);
  }

  /** 单条审核（调用方需处于事务中） */
  void review(Long id, boolean approve, Long operatorId, String comment) {
    ApplicationEntity app = applicationRepository.findByIdForUpdate(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
    String trimmed = comment == null || comment.isBlank() ? null : comment.trim();
    if (approve) {
      if (app.getStatus() != ApplicationStatus.AI_PASSED) {
        throw new BusinessException(ErrorCode.APPLICATION_STATUS_INVALID, "仅 AI 审核通过的投递可以审核通过");
      }
    } else if (!app.getStatus().canTransitTo(ApplicationStatus.HR_REJECTED)) {
      throw new BusinessException(ErrorCode.APPLICATION_STATUS_INVALID, "当前状态不能标记为不通过");
    }
    app.setHrReviewerId(operatorId);
    app.setHrComment(trimmed);
    app.setHrReviewedAt(LocalDateTime.now());
    ApplicationStatus target = approve ? ApplicationStatus.HR_APPROVED : ApplicationStatus.HR_REJECTED;
    stateMachine.transit(app, target, OperatorType.HR, operatorId,
        (approve ? "HR 审核通过" : "HR 审核不通过") + (trimmed != null ? "：" + trimmed : ""));
  }

  // ========== 线下面试 ==========

  /**
   * 安排 / 修改线下面试：写入面试日程并进入"已邀约线下面试"，同时触发邀约邮件（含日历附件）
   */
  @Transactional(rollbackFor = Exception.class)
  public ApplicationDetailDTO scheduleOffline(Long id, Long operatorId, OfflineInterviewRequest request) {
    ApplicationEntity app = applicationRepository.findByIdForUpdate(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
    if (app.getStatus() != ApplicationStatus.HR_APPROVED && app.getStatus() != ApplicationStatus.OFFLINE_SCHEDULED
        && app.getStatus() != ApplicationStatus.OFFLINE_CONFIRMED) {
      throw new BusinessException(ErrorCode.APPLICATION_STATUS_INVALID, "HR 审核通过后才能安排线下面试");
    }
    JobPositionEntity job = jobPositionRepository.findById(app.getJobId()).orElse(null);
    boolean reschedule = app.getStatus() != ApplicationStatus.HR_APPROVED;

    InterviewScheduleEntity schedule = scheduleRepository.findByApplicationId(id).orElseGet(InterviewScheduleEntity::new);
    schedule.setApplicationId(id);
    schedule.setCompanyName(mailSettingsService.resolve().companyName());
    schedule.setPosition(job != null ? job.getTitle() : "线下面试");
    schedule.setInterviewTime(request.interviewTime());
    schedule.setDurationMinutes(request.durationMinutes());
    schedule.setInterviewType("ONSITE");
    schedule.setLocationAddress(request.location().trim());
    schedule.setInterviewer(request.interviewer());
    schedule.setNotes(request.notes());
    schedule.setRoundNumber(request.roundNumber() != null ? request.roundNumber() : 1);
    schedule.setStatus(InterviewStatus.PENDING);
    schedule.setCandidateConfirmStatus(CandidateApplicationService.CONFIRM_PENDING);
    schedule.setCandidateNote(null);
    schedule.setConfirmedAt(null);
    scheduleRepository.save(schedule);

    stateMachine.transit(app, ApplicationStatus.OFFLINE_SCHEDULED, OperatorType.HR, operatorId,
        (reschedule ? "调整线下面试：" : "安排线下面试：") + request.interviewTime() + " @ " + request.location().trim());
    return detail(id);
  }

  // ========== 统计 ==========

  @Transactional(readOnly = true)
  public ApplicationStatsDTO stats(Long jobId) {
    Map<ApplicationStatus, Long> byStatus = new EnumMap<>(ApplicationStatus.class);
    Arrays.stream(ApplicationStatus.values()).forEach(s -> byStatus.put(s, 0L));
    applicationRepository.countByStatus(jobId).forEach(c -> byStatus.put(c.getStatus(), c.getTotal()));
    long total = byStatus.values().stream().mapToLong(Long::longValue).sum();
    return new ApplicationStatsDTO(total, byStatus, funnel(byStatus, total));
  }

  static List<ApplicationStatsDTO.FunnelStage> funnel(Map<ApplicationStatus, Long> byStatus, long total) {
    EnumSet<ApplicationStatus> offline = EnumSet.of(ApplicationStatus.OFFLINE_SCHEDULED,
        ApplicationStatus.OFFLINE_CONFIRMED);
    EnumSet<ApplicationStatus> hrApproved = EnumSet.copyOf(offline);
    hrApproved.add(ApplicationStatus.HR_APPROVED);
    EnumSet<ApplicationStatus> aiPassed = EnumSet.copyOf(hrApproved);
    aiPassed.add(ApplicationStatus.AI_PASSED);
    EnumSet<ApplicationStatus> interviewed = EnumSet.copyOf(aiPassed);
    interviewed.addAll(EnumSet.of(ApplicationStatus.AI_REVIEWING, ApplicationStatus.AI_REJECTED));
    EnumSet<ApplicationStatus> invited = EnumSet.copyOf(interviewed);
    invited.addAll(EnumSet.of(ApplicationStatus.INTERVIEW_INVITED, ApplicationStatus.INTERVIEWING,
        ApplicationStatus.EXPIRED));
    return List.of(
        new ApplicationStatsDTO.FunnelStage("SUBMITTED", "投递简历", total),
        new ApplicationStatsDTO.FunnelStage("INVITED", "进入在线面试", sum(byStatus, invited)),
        new ApplicationStatsDTO.FunnelStage("INTERVIEWED", "完成在线面试", sum(byStatus, interviewed)),
        new ApplicationStatsDTO.FunnelStage("AI_PASSED", "AI 审核通过", sum(byStatus, aiPassed)),
        new ApplicationStatsDTO.FunnelStage("HR_APPROVED", "HR 审核通过", sum(byStatus, hrApproved)),
        new ApplicationStatsDTO.FunnelStage("OFFLINE", "邀约线下面试", sum(byStatus, offline)));
  }

  private static long sum(Map<ApplicationStatus, Long> byStatus, Set<ApplicationStatus> statuses) {
    return statuses.stream().mapToLong(s -> byStatus.getOrDefault(s, 0L)).sum();
  }

  // ========== 工具方法 ==========

  private ApplicationEntity require(Long id) {
    return applicationRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.APPLICATION_NOT_FOUND));
  }

  private <T> T parse(String json, Class<T> type) {
    if (json == null || json.isBlank()) {
      return null;
    }
    try {
      return objectMapper.readValue(json, type);
    } catch (Exception e) {
      log.warn("解析投递结果 JSON 失败: type={}", type.getSimpleName());
      return null;
    }
  }

  static OfflineInterviewDTO toOffline(InterviewScheduleEntity s) {
    return new OfflineInterviewDTO(s.getId(), s.getInterviewTime(), s.getDurationMinutes(), s.getLocationAddress(),
        s.getInterviewType(), s.getInterviewer(), s.getRoundNumber(), s.getNotes(), s.getCandidateConfirmStatus(),
        s.getCandidateNote(), s.getConfirmedAt());
  }

  private static String displayName(AdminUserEntity user) {
    return user.getDisplayName() != null && !user.getDisplayName().isBlank()
        ? user.getDisplayName() : user.getUsername();
  }

  private static String operatorName(OperatorType type, Long operatorId, Map<Long, String> adminNames) {
    return switch (type) {
      case SYSTEM -> "系统";
      case AI -> "AI";
      case CANDIDATE -> "候选人";
      case HR -> operatorId != null ? adminNames.getOrDefault(operatorId, "HR") : "HR";
    };
  }
}
