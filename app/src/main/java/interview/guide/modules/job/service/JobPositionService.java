package interview.guide.modules.job.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.JobPositionMapper;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.job.model.JobPositionDTO;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.model.JobPositionRequest;
import interview.guide.modules.job.model.JobStatus;
import interview.guide.modules.job.model.PublicJobDTO;
import interview.guide.modules.job.model.PublicJobDetailDTO;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * 招聘岗位管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JobPositionService {

  private static final TypeReference<List<CategoryDTO>> CATEGORY_LIST_TYPE = new TypeReference<>() {};
  private static final String COPY_SUFFIX = "（副本）";
  private static final int TITLE_MAX_LENGTH = 128;

  private final JobPositionRepository jobPositionRepository;
  private final KnowledgeBaseRepository knowledgeBaseRepository;
  private final InterviewSkillService interviewSkillService;
  private final JobPositionMapper jobPositionMapper;
  private final ObjectMapper objectMapper;

  // ========== 后台 ==========

  @Transactional(readOnly = true)
  public List<JobPositionDTO> list(JobStatus status, String keyword) {
    String pattern = keyword == null || keyword.isBlank()
        ? "%"
        : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";
    List<JobPositionEntity> entities = status == null
        ? jobPositionRepository.searchAll(pattern)
        : jobPositionRepository.searchByStatus(status, pattern);
    return entities.stream().map(this::toDTO).toList();
  }

  @Transactional(readOnly = true)
  public JobPositionDTO get(Long id) {
    return toDTO(getOrThrow(id));
  }

  @Transactional
  public JobPositionDTO create(JobPositionRequest request, Long operatorId) {
    validateConfig(request);
    JobPositionEntity entity = new JobPositionEntity();
    applyRequest(request, entity);
    entity.setStatus(JobStatus.DRAFT);
    entity.setCreatedBy(operatorId);
    JobPositionEntity saved = jobPositionRepository.save(entity);
    log.info("创建岗位: id={}, title={}, operator={}", saved.getId(), saved.getTitle(), operatorId);
    return toDTO(saved);
  }

  @Transactional
  public JobPositionDTO update(Long id, JobPositionRequest request) {
    JobPositionEntity entity = getOrThrow(id);
    validateConfig(request);
    applyRequest(request, entity);
    if (entity.getStatus() == JobStatus.OPEN) {
      validatePublishable(entity);
    }
    log.info("更新岗位: id={}", id);
    return toDTO(jobPositionRepository.save(entity));
  }

  /**
   * 状态流转：DRAFT → OPEN / CLOSED，OPEN → CLOSED，CLOSED → OPEN
   */
  @Transactional
  public JobPositionDTO changeStatus(Long id, JobStatus target) {
    JobPositionEntity entity = getOrThrow(id);
    JobStatus current = entity.getStatus();
    if (current == target) {
      return toDTO(entity);
    }
    if (target == JobStatus.DRAFT) {
      throw new BusinessException(ErrorCode.JOB_STATUS_INVALID, "岗位发布后不能退回草稿，请关闭岗位");
    }
    if (target == JobStatus.OPEN) {
      validatePublishable(entity);
      if (entity.getPublishedAt() == null) {
        entity.setPublishedAt(LocalDateTime.now());
      }
    }
    entity.setStatus(target);
    log.info("岗位状态变更: id={}, {} -> {}", id, current, target);
    return toDTO(jobPositionRepository.save(entity));
  }

  @Transactional
  public JobPositionDTO copy(Long id, Long operatorId) {
    JobPositionEntity source = getOrThrow(id);
    JobPositionEntity copy = new JobPositionEntity();
    copy.setTitle(truncate(source.getTitle() + COPY_SUFFIX, TITLE_MAX_LENGTH));
    copy.setDepartment(source.getDepartment());
    copy.setLocation(source.getLocation());
    copy.setEmploymentType(source.getEmploymentType());
    copy.setHeadcount(source.getHeadcount());
    copy.setSalaryRange(source.getSalaryRange());
    copy.setJdText(source.getJdText());
    copy.setCategoriesJson(source.getCategoriesJson());
    copy.setSkillId(source.getSkillId());
    copy.setDifficulty(source.getDifficulty());
    copy.setQuestionCount(source.getQuestionCount());
    copy.setInterviewMode(source.getInterviewMode());
    copy.setKnowledgeBaseId(source.getKnowledgeBaseId());
    copy.setTimeLimitMinutes(source.getTimeLimitMinutes());
    copy.setLlmProvider(source.getLlmProvider());
    copy.setScreeningEnabled(source.isScreeningEnabled());
    copy.setScreeningThreshold(source.getScreeningThreshold());
    copy.setAiPassThreshold(source.getAiPassThreshold());
    copy.setStatus(JobStatus.DRAFT);
    copy.setCreatedBy(operatorId);
    JobPositionEntity saved = jobPositionRepository.save(copy);
    log.info("复制岗位: sourceId={}, newId={}", id, saved.getId());
    return toDTO(saved);
  }

  /**
   * 仅草稿岗位可删除；已发布岗位请关闭，以保留投递记录
   */
  @Transactional
  public void delete(Long id) {
    JobPositionEntity entity = getOrThrow(id);
    if (entity.getStatus() != JobStatus.DRAFT) {
      throw new BusinessException(ErrorCode.JOB_STATUS_INVALID, "只能删除草稿岗位，已发布岗位请关闭");
    }
    jobPositionRepository.delete(entity);
    log.info("删除岗位: id={}", id);
  }

  /**
   * JD 解析预览：调用 LLM 提取考察维度，HR 确认后随岗位保存。
   * 不开启事务（LLM 调用不得放在事务内）。
   */
  public List<CategoryDTO> parseJd(String jdText) {
    return interviewSkillService.parseJd(jdText);
  }

  // ========== 候选人门户 ==========

  @Transactional(readOnly = true)
  public List<PublicJobDTO> listPublic() {
    return jobPositionRepository.findAcceptingApplications(LocalDateTime.now()).stream()
        .map(jobPositionMapper::toPublicDTO)
        .toList();
  }

  @Transactional(readOnly = true)
  public PublicJobDetailDTO getPublic(Long id) {
    JobPositionEntity entity = jobPositionRepository.findById(id)
        .filter(job -> job.isAcceptingApplications(LocalDateTime.now()))
        .orElseThrow(() -> new BusinessException(ErrorCode.JOB_NOT_FOUND, "岗位不存在或已停止招聘"));
    return jobPositionMapper.toPublicDetailDTO(entity);
  }

  // ========== 内部方法 ==========

  /** 供后续投递 / 面试模块读取岗位配置 */
  @Transactional(readOnly = true)
  public JobPositionEntity getEntity(Long id) {
    return getOrThrow(id);
  }

  public List<CategoryDTO> readCategories(JobPositionEntity entity) {
    String json = entity.getCategoriesJson();
    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return objectMapper.readValue(json, CATEGORY_LIST_TYPE);
    } catch (Exception e) {
      log.warn("解析岗位考察维度失败: jobId={}", entity.getId(), e);
      return List.of();
    }
  }

  private void applyRequest(JobPositionRequest request, JobPositionEntity entity) {
    jobPositionMapper.updateEntity(request, entity);
    entity.setTitle(request.title().trim());
    entity.setDepartment(blankToNull(request.department()));
    entity.setLocation(blankToNull(request.location()));
    entity.setSalaryRange(blankToNull(request.salaryRange()));
    entity.setLlmProvider(blankToNull(request.llmProvider()));
    entity.setScreeningEnabled(Boolean.TRUE.equals(request.screeningEnabled()));
    // 防作弊开关缺省开启（旧客户端未传时保持默认）
    entity.setProctorAwayReminder(!Boolean.FALSE.equals(request.proctorAwayReminder()));
    entity.setProctorWatermark(!Boolean.FALSE.equals(request.proctorWatermark()));
    // 笔试配置：未传的字段保留实体当前值（新建时为默认值）
    if (request.writtenTestEnabled() != null) {
      entity.setWrittenTestEnabled(request.writtenTestEnabled());
    }
    if (request.writtenSingleCount() != null) {
      entity.setWrittenSingleCount(request.writtenSingleCount());
    }
    if (request.writtenMultiCount() != null) {
      entity.setWrittenMultiCount(request.writtenMultiCount());
    }
    if (request.writtenFillCount() != null) {
      entity.setWrittenFillCount(request.writtenFillCount());
    }
    if (request.writtenTimeLimitMinutes() != null) {
      entity.setWrittenTimeLimitMinutes(request.writtenTimeLimitMinutes());
    }
    if (request.writtenWeight() != null) {
      entity.setWrittenWeight(request.writtenWeight());
    }
    entity.setCategoriesJson(writeCategories(request.categories()));
  }

  /** 笔试单份题量上限，避免 LLM 单次生成过长 */
  static final int MAX_WRITTEN_QUESTIONS = 30;

  private void validateConfig(JobPositionRequest request) {
    if (!InterviewSkillService.CUSTOM_SKILL_ID.equals(request.skillId())) {
      // 未知 skillId 时抛出 BAD_REQUEST
      interviewSkillService.getSkill(request.skillId());
    }
    if (Boolean.TRUE.equals(request.screeningEnabled()) && request.screeningThreshold() == null) {
      throw new BusinessException(ErrorCode.JOB_CONFIG_INVALID, "启用简历初筛时必须设置初筛分数线");
    }
    if (request.knowledgeBaseId() != null && !knowledgeBaseRepository.existsById(request.knowledgeBaseId())) {
      throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND, "绑定的知识库题库不存在");
    }
    if (Boolean.TRUE.equals(request.writtenTestEnabled())) {
      int total = nz(request.writtenSingleCount()) + nz(request.writtenMultiCount()) + nz(request.writtenFillCount());
      if (total <= 0) {
        throw new BusinessException(ErrorCode.JOB_CONFIG_INVALID, "启用笔试时至少需要 1 道选择题或填空题");
      }
      if (total > MAX_WRITTEN_QUESTIONS) {
        throw new BusinessException(ErrorCode.JOB_CONFIG_INVALID, "笔试题目总数不能超过 " + MAX_WRITTEN_QUESTIONS + " 题");
      }
    }
  }

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }

  private void validatePublishable(JobPositionEntity entity) {
    if (InterviewSkillService.CUSTOM_SKILL_ID.equals(entity.getSkillId()) && readCategories(entity).isEmpty()) {
      throw new BusinessException(ErrorCode.JOB_CONFIG_INVALID, "自定义面试主题需要先解析 JD 生成考察维度");
    }
    if (entity.getDeadline() != null && !entity.getDeadline().isAfter(LocalDateTime.now())) {
      throw new BusinessException(ErrorCode.JOB_CONFIG_INVALID, "截止时间已过，请调整后再发布");
    }
  }

  private String writeCategories(List<CategoryDTO> categories) {
    if (categories == null || categories.isEmpty()) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(categories);
    } catch (Exception e) {
      throw new BusinessException(ErrorCode.BAD_REQUEST, "考察维度格式不正确");
    }
  }

  private JobPositionDTO toDTO(JobPositionEntity entity) {
    return jobPositionMapper.toDTO(entity, readCategories(entity));
  }

  private JobPositionEntity getOrThrow(Long id) {
    return jobPositionRepository.findById(id)
        .orElseThrow(() -> new BusinessException(ErrorCode.JOB_NOT_FOUND));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static String truncate(String value, int maxLength) {
    return value.length() <= maxLength ? value : value.substring(0, maxLength);
  }
}
