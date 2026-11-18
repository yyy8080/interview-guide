package interview.guide.modules.job.model;

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 后台岗位详情（含面试与审核配置）
 */
public record JobPositionDTO(
    Long id,
    String title,
    String department,
    String location,
    EmploymentType employmentType,
    Integer headcount,
    String salaryRange,
    String jdText,
    List<CategoryDTO> categories,
    String skillId,
    String difficulty,
    Integer questionCount,
    InterviewMode interviewMode,
    Long knowledgeBaseId,
    Integer timeLimitMinutes,
    String llmProvider,
    boolean screeningEnabled,
    Integer screeningThreshold,
    Integer aiPassThreshold,
    JobStatus status,
    LocalDateTime deadline,
    LocalDateTime publishedAt,
    Long createdBy,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {}
