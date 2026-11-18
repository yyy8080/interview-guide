package interview.guide.modules.job.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.mapper.JobPositionMapper;
import interview.guide.modules.interview.skill.InterviewSkillService;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.job.model.EmploymentType;
import interview.guide.modules.job.model.InterviewMode;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.model.JobPositionRequest;
import interview.guide.modules.job.model.JobStatus;
import interview.guide.modules.job.repository.JobPositionRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("岗位管理服务测试")
class JobPositionServiceTest {

  @Mock
  private JobPositionRepository jobPositionRepository;
  @Mock
  private KnowledgeBaseRepository knowledgeBaseRepository;
  @Mock
  private InterviewSkillService interviewSkillService;
  @Mock
  private JobPositionMapper jobPositionMapper;

  private JobPositionService service;

  @BeforeEach
  void setUp() {
    service = new JobPositionService(jobPositionRepository, knowledgeBaseRepository,
        interviewSkillService, jobPositionMapper, JsonMapper.builder().build());
  }

  private static JobPositionEntity job(JobStatus status, String categoriesJson) {
    JobPositionEntity entity = new JobPositionEntity();
    entity.setId(1L);
    entity.setTitle("Java 后端");
    entity.setJdText("JD");
    entity.setSkillId(InterviewSkillService.CUSTOM_SKILL_ID);
    entity.setDifficulty("mid");
    entity.setQuestionCount(8);
    entity.setAiPassThreshold(70);
    entity.setStatus(status);
    entity.setCategoriesJson(categoriesJson);
    return entity;
  }

  private static JobPositionRequest request(boolean screeningEnabled, Integer screeningThreshold) {
    return new JobPositionRequest("Java 后端", null, null, EmploymentType.FULL_TIME, 1, null, "JD 内容",
        List.of(new CategoryDTO("java", "Java 基础", "CORE", null, null)),
        InterviewSkillService.CUSTOM_SKILL_ID, "mid", 8, InterviewMode.TEXT, null, 45, null,
        screeningEnabled, screeningThreshold, 70, null);
  }

  @Test
  @DisplayName("按 JD 出题但未生成考察维度时不能发布")
  void publishRequiresCategoriesForCustomSkill() {
    when(jobPositionRepository.findById(1L)).thenReturn(Optional.of(job(JobStatus.DRAFT, null)));

    assertThatThrownBy(() -> service.changeStatus(1L, JobStatus.OPEN))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.JOB_CONFIG_INVALID.getCode());
    verify(jobPositionRepository, never()).save(any());
  }

  @Test
  @DisplayName("发布成功时记录首次发布时间")
  void publishSetsPublishedAt() {
    JobPositionEntity entity = job(JobStatus.DRAFT,
        "[{\"key\":\"java\",\"label\":\"Java 基础\",\"priority\":\"CORE\"}]");
    when(jobPositionRepository.findById(1L)).thenReturn(Optional.of(entity));
    when(jobPositionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.changeStatus(1L, JobStatus.OPEN);

    assertThat(entity.getStatus()).isEqualTo(JobStatus.OPEN);
    assertThat(entity.getPublishedAt()).isNotNull();
  }

  @Test
  @DisplayName("截止时间已过的岗位不能发布")
  void publishRejectsPastDeadline() {
    JobPositionEntity entity = job(JobStatus.CLOSED,
        "[{\"key\":\"java\",\"label\":\"Java 基础\",\"priority\":\"CORE\"}]");
    entity.setDeadline(LocalDateTime.now().minusDays(1));
    when(jobPositionRepository.findById(1L)).thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> service.changeStatus(1L, JobStatus.OPEN))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.JOB_CONFIG_INVALID.getCode());
  }

  @Test
  @DisplayName("已发布岗位不能退回草稿")
  void cannotRevertToDraft() {
    when(jobPositionRepository.findById(1L)).thenReturn(Optional.of(job(JobStatus.OPEN, null)));

    assertThatThrownBy(() -> service.changeStatus(1L, JobStatus.DRAFT))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.JOB_STATUS_INVALID.getCode());
  }

  @Test
  @DisplayName("只能删除草稿岗位")
  void deleteOnlyDraft() {
    when(jobPositionRepository.findById(1L)).thenReturn(Optional.of(job(JobStatus.CLOSED, null)));

    assertThatThrownBy(() -> service.delete(1L))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.JOB_STATUS_INVALID.getCode());
    verify(jobPositionRepository, never()).delete(any());
  }

  @Test
  @DisplayName("启用简历初筛时必须设置分数线")
  void screeningRequiresThreshold() {
    assertThatThrownBy(() -> service.create(request(true, null), 1L))
        .isInstanceOf(BusinessException.class)
        .extracting("code").isEqualTo(ErrorCode.JOB_CONFIG_INVALID.getCode());
    verify(jobPositionRepository, never()).save(any());
  }

  @Test
  @DisplayName("新建岗位默认为草稿，考察维度序列化保存")
  void createAsDraftWithCategories() {
    when(jobPositionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    service.create(request(false, null), 7L);

    org.mockito.ArgumentCaptor<JobPositionEntity> captor = org.mockito.ArgumentCaptor.forClass(JobPositionEntity.class);
    verify(jobPositionRepository).save(captor.capture());
    JobPositionEntity saved = captor.getValue();
    assertThat(saved.getStatus()).isEqualTo(JobStatus.DRAFT);
    assertThat(saved.getCreatedBy()).isEqualTo(7L);
    assertThat(saved.getCategoriesJson()).contains("\"key\":\"java\"");
    assertThat(service.readCategories(saved)).extracting(CategoryDTO::label).containsExactly("Java 基础");
  }
}
