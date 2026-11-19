package interview.guide.modules.recruitment.service;

import interview.guide.common.config.RecruitmentProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.result.PageResult;
import interview.guide.modules.auth.model.AdminUserEntity;
import interview.guide.modules.auth.repository.AdminUserRepository;
import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.model.JobStatus;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.mail.model.EmailLogEntity;
import interview.guide.modules.mail.model.EmailTemplateCode;
import interview.guide.modules.mail.repository.EmailLogRepository;
import interview.guide.modules.mail.service.MailOutboxService;
import interview.guide.modules.mail.service.MailSettingsService;
import interview.guide.modules.mail.service.MailSettingsService.ResolvedMailConfig;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.BatchInvitationResultDTO;
import interview.guide.modules.recruitment.model.BatchInvitationResultDTO.InvalidEmailItem;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 招聘定向面试邀请服务：
 * <ul>
 *   <li>HR 后台直接填写邮箱发送岗位邀请邮件（直投候选人邮箱，支持批量、重发、撤销）</li>
 *   <li>汇总并实时展示每位受邀候选人是否已参加在线笔试/面试</li>
 *   <li>执行严格的受邀邮箱准入控制：非受邀邮箱不允许登录验证、不允许投递简历、不允许进入在线笔试与面试</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecruitmentInvitationService {

  private static final int MAX_BATCH_EMAILS = 200;
  private static final int MAX_PAGE_SIZE = 100;
  private static final DateTimeFormatter MINUTE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
  private static final Pattern SPLIT_PATTERN = Pattern.compile("[\\s,;，；、]+");
  private static final Pattern EMAIL_PATTERN =
      Pattern.compile("^[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}$");

  private final RecruitmentInvitationRepository invitationRepository;
  private final JobPositionRepository jobPositionRepository;
  private final CandidateRepository candidateRepository;
  private final ApplicationRepository applicationRepository;
  private final WrittenTestRepository writtenTestRepository;
  private final EmailLogRepository emailLogRepository;
  private final AdminUserRepository adminUserRepository;
  private final MailOutboxService mailOutboxService;
  private final MailSettingsService mailSettingsService;
  private final RecruitmentProperties properties;

  record ParticipationResolution(boolean participated, InvitationParticipationStatus status, String detail) {
  }

  // ========== 后台发送 / 重发 / 撤销邀请 ==========

  @Transactional(rollbackFor = Exception.class)
  public BatchInvitationResultDTO sendInvitations(CreateInvitationsRequest request, Long operatorId) {
    JobPositionEntity job = jobPositionRepository.findById(request.jobId())
        .orElseThrow(() -> new BusinessException(ErrorCode.JOB_NOT_FOUND));
    LocalDateTime now = LocalDateTime.now();
    if (!job.isAcceptingApplications(now)) {
      throw new BusinessException(ErrorCode.JOB_NOT_ACCEPTING,
          job.getStatus() == JobStatus.DRAFT ? "该岗位仍为草稿状态，请先发布岗位后再发送面试邀请" : "该岗位已停止接收投递");
    }

    Map<String, String> recipientNames = extractRecipientNames(request.recipients());
    List<String> rawTokens = extractRawEmails(request.emails(), request.emailsText(), request.recipients());
    if (rawTokens.isEmpty()) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "请填写至少一个候选人邮箱");
    }
    if (rawTokens.size() > MAX_BATCH_EMAILS) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "单次最多批量邀请 " + MAX_BATCH_EMAILS + " 个邮箱");
    }

    Set<String> validEmails = new LinkedHashSet<>();
    List<InvalidEmailItem> invalidItems = new ArrayList<>();
    for (String token : rawTokens) {
      String normalized = token.trim().toLowerCase(Locale.ROOT);
      if (normalized.isEmpty()) {
        continue;
      }
      if (!isValidEmail(normalized)) {
        invalidItems.add(new InvalidEmailItem(token.trim(), "邮箱格式不正确"));
      } else {
        validEmails.add(normalized);
      }
    }

    if (validEmails.isEmpty()) {
      String firstInvalid = invalidItems.isEmpty() ? "" : "：" + invalidItems.get(0).email();
      throw new BusinessException(ErrorCode.BAD_REQUEST, "邮箱格式不正确" + firstInvalid);
    }

    int expireDays = request.expireDays() != null
        ? request.expireDays()
        : Math.max(1, properties.getInviteExpireDays());
    LocalDateTime expiresAt = now.plusDays(expireDays);
    boolean skipScreening = Boolean.TRUE.equals(request.skipScreening());
    String note = trimToNull(request.note());
    String singleName = validEmails.size() == 1 ? sanitizeCandidateName(request.candidateName()) : null;

    ResolvedMailConfig mailConfig = mailSettingsService.resolve();
    List<RecruitmentInvitationEntity> savedEntities = new ArrayList<>();
    int createdCount = 0;
    int resentCount = 0;

    for (String email : validEmails) {
      String resolvedName = recipientNames.getOrDefault(email, singleName);
      Optional<RecruitmentInvitationEntity> existingOpt =
          invitationRepository.findByJobIdAndEmail(job.getId(), email);
      RecruitmentInvitationEntity entity;
      if (existingOpt.isPresent()) {
        entity = existingOpt.get();
        entity.setStatus(InvitationRecordStatus.ACTIVE);
        entity.setRevokedAt(null);
        entity.setInvitedBy(operatorId);
        entity.setInvitedAt(now);
        entity.setExpiresAt(expiresAt);
        entity.setSkipScreening(skipScreening);
        entity.setNote(note);
        if (resolvedName != null) {
          entity.setCandidateName(resolvedName);
        }
        entity.setSendCount(entity.getSendCount() + 1);
        resentCount++;
      } else {
        entity = new RecruitmentInvitationEntity();
        entity.setJobId(job.getId());
        entity.setEmail(email);
        entity.setCandidateName(resolvedName);
        entity.setInviteCode(UUID.randomUUID().toString().replace("-", ""));
        entity.setStatus(InvitationRecordStatus.ACTIVE);
        entity.setSkipScreening(skipScreening);
        entity.setNote(note);
        entity.setInvitedBy(operatorId);
        entity.setInvitedAt(now);
        entity.setExpiresAt(expiresAt);
        entity.setSendCount(1);
        createdCount++;
      }

      linkExistingApplicationIfPresent(entity, job.getId(), email);
      Long emailLogId = enqueueInvitationEmail(entity, job, mailConfig);
      entity.setLastEmailLogId(emailLogId);
      savedEntities.add(invitationRepository.save(entity));
    }

    log.info("HR 发送面试邀请: jobId={}, operatorId={}, created={}, resent={}, invalid={}",
        job.getId(), operatorId, createdCount, resentCount, invalidItems.size());
    List<InvitationListItemDTO> items = toDTOList(savedEntities, now);
    return new BatchInvitationResultDTO(createdCount, resentCount, mailConfig.enabled(), items, invalidItems);
  }

  @Transactional(rollbackFor = Exception.class)
  public InvitationListItemDTO resendInvitation(Long id, Long operatorId) {
    RecruitmentInvitationEntity entity = invitationRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "邀请记录不存在"));
    JobPositionEntity job = jobPositionRepository.findById(entity.getJobId())
        .orElseThrow(() -> new BusinessException(ErrorCode.JOB_NOT_FOUND));
    LocalDateTime now = LocalDateTime.now();
    if (!job.isAcceptingApplications(now)) {
      throw new BusinessException(ErrorCode.JOB_NOT_ACCEPTING, "该岗位已关闭或截止，无法重发邀请");
    }

    entity.setStatus(InvitationRecordStatus.ACTIVE);
    entity.setRevokedAt(null);
    entity.setInvitedBy(operatorId);
    entity.setInvitedAt(now);
    int defaultDays = Math.max(1, properties.getInviteExpireDays());
    if (entity.getExpiresAt() == null || entity.getExpiresAt().isBefore(now.plusDays(1))) {
      entity.setExpiresAt(now.plusDays(defaultDays));
    }
    entity.setSendCount(entity.getSendCount() + 1);
    linkExistingApplicationIfPresent(entity, job.getId(), entity.getEmail());

    ResolvedMailConfig mailConfig = mailSettingsService.resolve();
    Long emailLogId = enqueueInvitationEmail(entity, job, mailConfig);
    entity.setLastEmailLogId(emailLogId);
    RecruitmentInvitationEntity saved = invitationRepository.save(entity);
    log.info("HR 重发面试邀请邮件: invitationId={}, jobId={}, operatorId={}", id, job.getId(), operatorId);
    return toDTOList(List.of(saved), now).get(0);
  }

  @Transactional(rollbackFor = Exception.class)
  public InvitationListItemDTO revokeInvitation(Long id, Long operatorId) {
    RecruitmentInvitationEntity entity = invitationRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "邀请记录不存在"));
    LocalDateTime now = LocalDateTime.now();
    entity.setStatus(InvitationRecordStatus.REVOKED);
    entity.setRevokedAt(now);
    RecruitmentInvitationEntity saved = invitationRepository.save(entity);
    log.info("HR 撤销面试邀请: invitationId={}, jobId={}, operatorId={}", id, entity.getJobId(), operatorId);
    return toDTOList(List.of(saved), now).get(0);
  }

  // ========== 后台列表与统计 ==========

  @Transactional(readOnly = true)
  public PageResult<InvitationListItemDTO> list(Long jobId, InvitationParticipationFilter participationFilter,
                                                String keyword, int page, int size) {
    int safePage = Math.max(page, 1);
    int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    String pattern = "%" + (keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT)) + "%";
    LocalDateTime now = LocalDateTime.now();

    if (participationFilter == null) {
      PageRequest pageable = PageRequest.of(
          safePage - 1,
          safeSize,
          Sort.by(Sort.Direction.DESC, "invitedAt").and(Sort.by(Sort.Direction.DESC, "id")));
      Page<RecruitmentInvitationEntity> entityPage = invitationRepository.search(jobId, pattern, pageable);
      List<InvitationListItemDTO> dtoItems = toDTOList(entityPage.getContent(), now);
      return new PageResult<>(dtoItems, entityPage.getTotalElements(), safePage, safeSize);
    }

    List<RecruitmentInvitationEntity> allMatched = invitationRepository.searchAll(jobId, pattern);
    List<InvitationListItemDTO> allDtos = toDTOList(allMatched, now);
    List<InvitationListItemDTO> filtered = allDtos.stream()
        .filter(item -> participationFilter.matches(item.participationStatus()))
        .toList();
    int fromIndex = Math.min((safePage - 1) * safeSize, filtered.size());
    int toIndex = Math.min(fromIndex + safeSize, filtered.size());
    return new PageResult<>(filtered.subList(fromIndex, toIndex), filtered.size(), safePage, safeSize);
  }

  @Transactional(readOnly = true)
  public InvitationStatsDTO stats(Long jobId) {
    List<RecruitmentInvitationEntity> all = invitationRepository.searchAll(jobId, "%%");
    if (all.isEmpty()) {
      return new InvitationStatsDTO(0, 0, 0, 0, 0, 0);
    }
    LocalDateTime now = LocalDateTime.now();
    Map<Long, ApplicationEntity> applicationsById = loadApplicationsMap(all);
    Map<Long, WrittenTestEntity> writtenByAppId = loadWrittenTestsMap(applicationsById.keySet());

    long participated = 0;
    long pendingInterview = 0;
    long notApplied = 0;
    long screenRejected = 0;
    long expiredOrRevoked = 0;

    for (RecruitmentInvitationEntity inv : all) {
      ApplicationEntity app = inv.getApplicationId() != null ? applicationsById.get(inv.getApplicationId()) : null;
      WrittenTestEntity written = app != null ? writtenByAppId.get(app.getId()) : null;
      ParticipationResolution res = resolveParticipation(inv, app, written, now);
      if (InvitationParticipationFilter.PARTICIPATED.matches(res.status())) {
        participated++;
      } else if (InvitationParticipationFilter.PENDING_INTERVIEW.matches(res.status())) {
        pendingInterview++;
      } else if (InvitationParticipationFilter.NOT_APPLIED.matches(res.status())) {
        notApplied++;
      } else if (InvitationParticipationFilter.SCREEN_REJECTED.matches(res.status())) {
        screenRejected++;
      } else {
        expiredOrRevoked++;
      }
    }
    return new InvitationStatsDTO(all.size(), participated, pendingInterview, notApplied,
        screenRejected, expiredOrRevoked);
  }

  // ========== 候选人受邀准入校验（非邀请邮箱禁止登录、投递、进入面试） ==========

  /**
   * 候选人邮箱获取登录验证码或验证登录前校验：
   * 开启受邀模式时，仅收到有效面试邀请的邮箱可登录进入门户。
   */
  @Transactional(readOnly = true)
  public void assertCanAuthenticate(String normalizedEmail) {
    if (!properties.isInviteOnly()) {
      return;
    }
    List<RecruitmentInvitationEntity> invitations =
        invitationRepository.findByEmailOrderByInvitedAtDesc(normalizedEmail);
    if (invitations.isEmpty()) {
      throw new BusinessException(ErrorCode.CANDIDATE_NOT_INVITED,
          "该邮箱未收到面试邀请，非受邀邮箱不允许投递简历与参加面试");
    }
    LocalDateTime now = LocalDateTime.now();
    List<RecruitmentInvitationEntity> activeInvites = invitations.stream()
        .filter(inv -> inv.getStatus() == InvitationRecordStatus.ACTIVE)
        .toList();
    if (activeInvites.isEmpty()) {
      throw new BusinessException(ErrorCode.CANDIDATE_NOT_INVITED,
          "您的面试邀请已被撤销，当前邮箱无法投递简历或参加面试");
    }
    boolean hasValidOrApplied = activeInvites.stream().anyMatch(inv ->
        inv.getApplicationId() != null
            || inv.getExpiresAt() == null
            || !inv.getExpiresAt().isBefore(now));
    if (!hasValidOrApplied) {
      // 兜底检查是否已有历史投递记录
      boolean hasExistingApplication = candidateRepository.findByEmail(normalizedEmail)
          .map(c -> !applicationRepository.findByCandidateIdOrderBySubmittedAtDesc(c.getId()).isEmpty())
          .orElse(false);
      if (!hasExistingApplication) {
        throw new BusinessException(ErrorCode.CANDIDATE_NOT_INVITED,
            "您的面试邀请已超过有效期，请联系招聘方重新发送邀请");
      }
    }
  }

  /**
   * 候选人投递或修改重提简历前校验是否拥有该具体岗位的受邀资格。
   *
   * @param firstSubmit true 表示首次投递（额外校验邀请未过期），false 表示初筛未通过重提简历
   * @return 匹配到的有效邀请（若未开启受邀制且无邀请记录则为空）
   */
  @Transactional(readOnly = true)
  public Optional<RecruitmentInvitationEntity> assertCanApplyAndGet(Long jobId, Long candidateId,
                                                                    boolean firstSubmit) {
    CandidateEntity candidate = candidateRepository.findById(candidateId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CANDIDATE_UNAUTHORIZED));
    Optional<RecruitmentInvitationEntity> found =
        invitationRepository.findByJobIdAndEmail(jobId, candidate.getEmail());

    if (!properties.isInviteOnly()) {
      return found.filter(inv -> inv.getStatus() == InvitationRecordStatus.ACTIVE);
    }

    RecruitmentInvitationEntity invitation = found.orElseThrow(() ->
        new BusinessException(ErrorCode.CANDIDATE_NOT_INVITED,
            "当前邮箱未收到该岗位的面试邀请，非受邀邮箱不允许投递简历面试"));
    if (invitation.getStatus() == InvitationRecordStatus.REVOKED) {
      throw new BusinessException(ErrorCode.CANDIDATE_NOT_INVITED,
          "该岗位的面试邀请已被撤销，当前邮箱不允许投递简历面试");
    }
    if (firstSubmit && invitation.getExpiresAt() != null
        && invitation.getExpiresAt().isBefore(LocalDateTime.now())) {
      throw new BusinessException(ErrorCode.CANDIDATE_NOT_INVITED,
          "该岗位的面试邀请已过期，请联系招聘方重新发送邀请");
    }
    return Optional.of(invitation);
  }

  /**
   * 候选人创建投递单后将 applicationId 回写关联到对应邀请记录（在调用方事务内执行）
   */
  @Transactional(rollbackFor = Exception.class)
  public void linkApplication(Long jobId, Long candidateId, Long applicationId, String candidateName) {
    candidateRepository.findById(candidateId).ifPresent(candidate ->
        invitationRepository.findByJobIdAndEmail(jobId, candidate.getEmail()).ifPresent(inv -> {
          inv.setApplicationId(applicationId);
          if (candidateName != null && !candidateName.isBlank()) {
            inv.setCandidateName(candidateName.trim());
          }
          invitationRepository.save(inv);
        }));
  }

  /**
   * 候选人进入在线笔试或在线文字/语音面试前校验受邀资格：
   * 仅收到本岗位有效（未撤销）邀请的邮箱允许进入面试。
   */
  @Transactional(readOnly = true)
  public void assertCanAccessInterview(ApplicationEntity application) {
    if (!properties.isInviteOnly()) {
      return;
    }
    CandidateEntity candidate = candidateRepository.findById(application.getCandidateId())
        .orElseThrow(() -> new BusinessException(ErrorCode.CANDIDATE_UNAUTHORIZED));
    RecruitmentInvitationEntity invitation = invitationRepository
        .findByJobIdAndEmail(application.getJobId(), candidate.getEmail())
        .orElseThrow(() -> new BusinessException(ErrorCode.CANDIDATE_NOT_INVITED,
            "仅受邀邮箱允许进入在线面试，当前邮箱未收到本岗位面试邀请"));
    if (invitation.getStatus() == InvitationRecordStatus.REVOKED) {
      throw new BusinessException(ErrorCode.CANDIDATE_NOT_INVITED,
          "您的面试邀请已被撤销，无法进入在线面试");
    }
  }

  /**
   * WebSocket 握手静默布尔校验
   */
  @Transactional(readOnly = true)
  public boolean canAccessInterviewQuietly(ApplicationEntity application) {
    try {
      assertCanAccessInterview(application);
      return true;
    } catch (BusinessException e) {
      return false;
    }
  }

  /**
   * 候选人在岗位详情页查询当前邮箱是否具备本岗位投递与面试资格
   */
  @Transactional(readOnly = true)
  public CandidateJobInvitationStatusDTO checkCandidateJobStatus(Long jobId, Long candidateId) {
    CandidateEntity candidate = candidateRepository.findById(candidateId)
        .orElseThrow(() -> new BusinessException(ErrorCode.CANDIDATE_UNAUTHORIZED));
    Optional<RecruitmentInvitationEntity> found =
        invitationRepository.findByJobIdAndEmail(jobId, candidate.getEmail());

    if (!properties.isInviteOnly()) {
      boolean invited = found.filter(inv -> inv.getStatus() == InvitationRecordStatus.ACTIVE).isPresent();
      boolean skip = found.filter(inv -> inv.getStatus() == InvitationRecordStatus.ACTIVE)
          .map(RecruitmentInvitationEntity::isSkipScreening).orElse(false);
      return new CandidateJobInvitationStatusDTO(false, true, invited, false, false, skip,
          found.map(RecruitmentInvitationEntity::getExpiresAt).orElse(null), null);
    }

    if (found.isEmpty()) {
      return new CandidateJobInvitationStatusDTO(true, false, false, false, false, false, null,
          "当前邮箱（" + candidate.getEmail() + "）未收到本岗位的面试邀请，非受邀邮箱不允许投递简历与参加面试。");
    }
    RecruitmentInvitationEntity inv = found.get();
    if (inv.getStatus() == InvitationRecordStatus.REVOKED) {
      return new CandidateJobInvitationStatusDTO(true, false, false, false, true, false, inv.getExpiresAt(),
          "当前邮箱在本岗位的面试邀请已被撤销，无法投递简历或进入面试。");
    }
    boolean expired = inv.getApplicationId() == null
        && inv.getExpiresAt() != null
        && inv.getExpiresAt().isBefore(LocalDateTime.now());
    if (expired) {
      return new CandidateJobInvitationStatusDTO(true, false, true, true, false, inv.isSkipScreening(),
          inv.getExpiresAt(), "您的本岗位面试邀请已超过有效期，请联系招聘方重新发送邀请。");
    }
    return new CandidateJobInvitationStatusDTO(true, true, true, false, false, inv.isSkipScreening(),
        inv.getExpiresAt(), "您的邮箱已获本岗位面试邀请，请按要求如实提交简历。");
  }

  // ========== 内部辅助方法 ==========

  private void linkExistingApplicationIfPresent(RecruitmentInvitationEntity entity, Long jobId, String email) {
    if (entity.getApplicationId() != null) {
      return;
    }
    candidateRepository.findByEmail(email).ifPresent(candidate -> {
      if (entity.getCandidateName() == null && candidate.getName() != null) {
        entity.setCandidateName(candidate.getName());
      }
      applicationRepository.findByJobIdAndCandidateId(jobId, candidate.getId())
          .ifPresent(app -> entity.setApplicationId(app.getId()));
    });
  }

  private Long enqueueInvitationEmail(RecruitmentInvitationEntity entity, JobPositionEntity job,
                                      ResolvedMailConfig mailConfig) {
    String base = trimTrailingSlash(mailConfig.portalBaseUrl());
    String redirect = "/careers/" + job.getId();
    String inviteUrl = base + "/careers/login?email="
        + URLEncoder.encode(entity.getEmail(), StandardCharsets.UTF_8)
        + "&redirect=" + URLEncoder.encode(redirect, StandardCharsets.UTF_8);

    String greeting = entity.getCandidateName() != null && !entity.getCandidateName().isBlank()
        ? entity.getCandidateName().trim() + "，您好"
        : "候选人，您好";
    String expireText = entity.getExpiresAt() != null ? entity.getExpiresAt().format(MINUTE_FMT) : "岗位截止前";
    String modeText = describeInterviewMode(job);
    String hrNote = entity.getNote() != null && !entity.getNote().isBlank()
        ? "HR 补充说明：" + entity.getNote().trim()
        : "";

    Map<String, String> vars = new HashMap<>();
    vars.put("candidateGreeting", greeting);
    vars.put("candidateEmail", entity.getEmail());
    vars.put("jobTitle", job.getTitle());
    vars.put("inviteUrl", inviteUrl);
    vars.put("expireAt", expireText);
    vars.put("interviewMode", modeText);
    vars.put("hrNote", hrNote);

    return mailOutboxService.enqueue(entity.getApplicationId(), entity.getEmail(),
        EmailTemplateCode.CANDIDATE_INVITATION, vars, null).orElse(null);
  }

  private List<InvitationListItemDTO> toDTOList(List<RecruitmentInvitationEntity> entities, LocalDateTime now) {
    if (entities.isEmpty()) {
      return List.of();
    }
    Map<Long, JobPositionEntity> jobs = jobPositionRepository.findAllById(
            entities.stream().map(RecruitmentInvitationEntity::getJobId).distinct().toList())
        .stream().collect(Collectors.toMap(JobPositionEntity::getId, Function.identity()));

    Map<Long, ApplicationEntity> applicationsById = loadApplicationsMap(entities);
    Map<Long, WrittenTestEntity> writtenByAppId = loadWrittenTestsMap(applicationsById.keySet());

    List<Long> emailLogIds = entities.stream()
        .map(RecruitmentInvitationEntity::getLastEmailLogId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    Map<Long, EmailLogEntity> emailLogs = emailLogIds.isEmpty()
        ? Map.of()
        : emailLogRepository.findAllById(emailLogIds).stream()
            .collect(Collectors.toMap(EmailLogEntity::getId, Function.identity()));

    List<Long> operatorIds = entities.stream()
        .map(RecruitmentInvitationEntity::getInvitedBy)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    Map<Long, AdminUserEntity> operators = operatorIds.isEmpty()
        ? Map.of()
        : adminUserRepository.findAllById(operatorIds).stream()
            .collect(Collectors.toMap(AdminUserEntity::getId, Function.identity()));

    return entities.stream().map(inv -> {
      JobPositionEntity job = jobs.get(inv.getJobId());
      ApplicationEntity app = inv.getApplicationId() != null ? applicationsById.get(inv.getApplicationId()) : null;
      WrittenTestEntity written = app != null ? writtenByAppId.get(app.getId()) : null;
      EmailLogEntity emailLog = inv.getLastEmailLogId() != null ? emailLogs.get(inv.getLastEmailLogId()) : null;
      AdminUserEntity operator = inv.getInvitedBy() != null ? operators.get(inv.getInvitedBy()) : null;
      ParticipationResolution res = resolveParticipation(inv, app, written, now);

      String candidateName = app != null && app.getCandidateName() != null
          ? app.getCandidateName()
          : inv.getCandidateName();
      String operatorName = operator == null
          ? null
          : (operator.getDisplayName() != null && !operator.getDisplayName().isBlank()
              ? operator.getDisplayName()
              : operator.getUsername());

      return new InvitationListItemDTO(
          inv.getId(),
          inv.getJobId(),
          job != null ? job.getTitle() : "（岗位已删除）",
          inv.getEmail(),
          candidateName,
          inv.getStatus(),
          inv.isSkipScreening(),
          inv.getNote(),
          operatorName,
          inv.getInvitedAt(),
          inv.getExpiresAt(),
          inv.getRevokedAt(),
          inv.getSendCount(),
          emailLog != null ? emailLog.getStatus() : null,
          emailLog != null ? emailLog.getSentAt() : null,
          emailLog != null ? emailLog.getError() : null,
          res.participated(),
          res.status(),
          res.detail(),
          app != null ? app.getId() : null,
          app != null ? app.getStatus() : null,
          app != null ? app.getInterviewMode() : null,
          app != null ? app.getScreeningScore() : null,
          app != null ? app.getWrittenScore() : null,
          app != null ? app.getAiScore() : null,
          app != null ? app.getSubmittedAt() : null,
          app != null ? app.getInterviewStartedAt() : null,
          app != null ? app.getInterviewCompletedAt() : null
      );
    }).toList();
  }

  private Map<Long, ApplicationEntity> loadApplicationsMap(List<RecruitmentInvitationEntity> invitations) {
    Map<Long, ApplicationEntity> result = new HashMap<>();
    List<Long> explicitAppIds = invitations.stream()
        .map(RecruitmentInvitationEntity::getApplicationId)
        .filter(Objects::nonNull)
        .distinct()
        .toList();
    if (!explicitAppIds.isEmpty()) {
      applicationRepository.findAllById(explicitAppIds).forEach(app -> result.put(app.getId(), app));
    }
    // 兜底匹配曾先创建投递但尚未回写 applicationId 的记录
    for (RecruitmentInvitationEntity inv : invitations) {
      if (inv.getApplicationId() == null) {
        candidateRepository.findByEmail(inv.getEmail())
            .flatMap(c -> applicationRepository.findByJobIdAndCandidateId(inv.getJobId(), c.getId()))
            .ifPresent(app -> {
              inv.setApplicationId(app.getId());
              result.put(app.getId(), app);
            });
      }
    }
    return result;
  }

  private Map<Long, WrittenTestEntity> loadWrittenTestsMap(Collection<Long> applicationIds) {
    if (applicationIds == null || applicationIds.isEmpty()) {
      return Map.of();
    }
    return writtenTestRepository.findByApplicationIdIn(applicationIds).stream()
        .collect(Collectors.toMap(WrittenTestEntity::getApplicationId, Function.identity(), (a, b) -> a));
  }

  static ParticipationResolution resolveParticipation(RecruitmentInvitationEntity inv,
                                                      ApplicationEntity app,
                                                      WrittenTestEntity writtenTest,
                                                      LocalDateTime now) {
    if (app != null) {
      ApplicationStatus status = app.getStatus();
      if (status == ApplicationStatus.AI_REVIEWING
          || status == ApplicationStatus.AI_PASSED
          || status == ApplicationStatus.AI_REJECTED
          || status == ApplicationStatus.HR_APPROVED
          || status == ApplicationStatus.OFFLINE_SCHEDULED
          || status == ApplicationStatus.OFFLINE_CONFIRMED
          || (status == ApplicationStatus.HR_REJECTED
              && (app.getInterviewStartedAt() != null || writtenTest != null))) {
        String modeLabel = app.getInterviewMode() == InterviewMode.VOICE ? "语音面试" : "文字面试";
        String scoreText = app.getAiScore() != null ? "（AI 综合分 " + app.getAiScore() + "）" : "";
        return new ParticipationResolution(true, InvitationParticipationStatus.COMPLETED,
            "已参加 · 已完成" + modeLabel + scoreText);
      }
      if (status == ApplicationStatus.INTERVIEWING) {
        String modeLabel = app.getInterviewMode() == InterviewMode.VOICE ? "语音面试" : "文字面试";
        return new ParticipationResolution(true, InvitationParticipationStatus.IN_INTERVIEW,
            "已参加 · 在线" + modeLabel + "进行中");
      }
      if (status == ApplicationStatus.INTERVIEW_INVITED && writtenTest != null) {
        if (writtenTest.getStatus() == WrittenTestStatus.IN_PROGRESS) {
          return new ParticipationResolution(true, InvitationParticipationStatus.IN_INTERVIEW,
              "已参加 · 在线笔试作答中");
        }
        String wScore = writtenTest.getScore() != null ? "（笔试 " + writtenTest.getScore() + " 分）" : "";
        return new ParticipationResolution(true, InvitationParticipationStatus.IN_INTERVIEW,
            "已参加 · 已交笔试待开始面试" + wScore);
      }
    }

    if (inv.getStatus() == InvitationRecordStatus.REVOKED) {
      return new ParticipationResolution(false, InvitationParticipationStatus.REVOKED, "已撤销邀请");
    }

    if (app == null) {
      if (inv.getExpiresAt() != null && inv.getExpiresAt().isBefore(now)) {
        return new ParticipationResolution(false, InvitationParticipationStatus.EXPIRED,
            "未参加 · 邀请已过期未投递");
      }
      return new ParticipationResolution(false, InvitationParticipationStatus.NOT_APPLIED,
          "未参加 · 尚未投递简历");
    }

    return switch (app.getStatus()) {
      case SUBMITTED, SCREENING -> new ParticipationResolution(false, InvitationParticipationStatus.SCREENING,
          "未参加 · 已投递简历，AI 初筛中");
      case SCREEN_REJECTED -> new ParticipationResolution(false, InvitationParticipationStatus.SCREEN_REJECTED,
          "未参加 · 简历初筛未通过"
              + (app.getScreeningScore() != null ? "（" + app.getScreeningScore() + " 分）" : ""));
      case INTERVIEW_INVITED -> {
        if (app.getInviteExpiresAt() != null && app.getInviteExpiresAt().isBefore(now)) {
          yield new ParticipationResolution(false, InvitationParticipationStatus.EXPIRED,
              "未参加 · 在线面试逾期未开考");
        }
        yield new ParticipationResolution(false, InvitationParticipationStatus.AWAITING_INTERVIEW,
            "未参加 · 已通过初筛，待开始答题");
      }
      case EXPIRED -> new ParticipationResolution(false, InvitationParticipationStatus.EXPIRED,
          "未参加 · 在线面试逾期未开考");
      case HR_REJECTED -> new ParticipationResolution(false, InvitationParticipationStatus.SCREEN_REJECTED,
          "未参加 · 开考前已淘汰");
      default -> new ParticipationResolution(false, InvitationParticipationStatus.AWAITING_INTERVIEW,
          "未参加");
    };
  }

  public static boolean isValidEmail(String email) {
    if (email == null) {
      return false;
    }
    String normalized = email.trim().toLowerCase(Locale.ROOT);
    return !normalized.isEmpty() && normalized.length() <= 255 && EMAIL_PATTERN.matcher(normalized).matches();
  }

  static List<String> extractRawEmails(List<String> emails, String emailsText) {
    return extractRawEmails(emails, emailsText, null);
  }

  static List<String> extractRawEmails(List<String> emails, String emailsText,
                                       List<CreateInvitationsRequest.RecipientItem> recipients) {
    List<String> tokens = new ArrayList<>();
    if (recipients != null) {
      for (CreateInvitationsRequest.RecipientItem item : recipients) {
        if (item != null && item.email() != null && !item.email().isBlank()) {
          for (String part : SPLIT_PATTERN.split(item.email().trim())) {
            if (!part.isBlank()) {
              tokens.add(part);
            }
          }
        }
      }
    }
    if (emails != null) {
      for (String item : emails) {
        if (item != null && !item.isBlank()) {
          for (String part : SPLIT_PATTERN.split(item.trim())) {
            if (!part.isBlank()) {
              tokens.add(part);
            }
          }
        }
      }
    }
    if (emailsText != null && !emailsText.isBlank()) {
      for (String part : SPLIT_PATTERN.split(emailsText.trim())) {
        if (!part.isBlank()) {
          tokens.add(part);
        }
      }
    }
    return tokens;
  }

  private static Map<String, String> extractRecipientNames(
      List<CreateInvitationsRequest.RecipientItem> recipients) {
    if (recipients == null || recipients.isEmpty()) {
      return Map.of();
    }
    Map<String, String> names = new HashMap<>();
    for (CreateInvitationsRequest.RecipientItem item : recipients) {
      if (item == null || item.email() == null || item.email().isBlank()) {
        continue;
      }
      String name = sanitizeCandidateName(item.candidateName());
      if (name == null) {
        continue;
      }
      String normalizedEmail = item.email().trim().toLowerCase(Locale.ROOT);
      names.putIfAbsent(normalizedEmail, name);
    }
    return names;
  }

  private static String sanitizeCandidateName(String value) {
    String trimmed = trimToNull(value);
    if (trimmed == null) {
      return null;
    }
    return trimmed.length() > 64 ? trimmed.substring(0, 64) : trimmed;
  }

  private static String describeInterviewMode(JobPositionEntity job) {
    String writtenPrefix = job.isWrittenTestEnabled() ? "在线笔试 + " : "";
    InterviewMode mode = job.getInterviewMode();
    if (mode == InterviewMode.VOICE) {
      return writtenPrefix + "实时语音 AI 面试";
    }
    if (mode == InterviewMode.TEXT) {
      return writtenPrefix + "文字问答 AI 面试";
    }
    return writtenPrefix + "文字问答 / 实时语音 AI 面试（可自选）";
  }

  private static String trimTrailingSlash(String url) {
    if (url == null || url.isBlank()) {
      return "http://localhost:5173";
    }
    String trimmed = url.trim();
    while (trimmed.endsWith("/")) {
      trimmed = trimmed.substring(0, trimmed.length() - 1);
    }
    return trimmed;
  }

  private static String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
