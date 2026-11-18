package interview.guide.modules.job.model;

import java.time.LocalDateTime;

/**
 * 候选人门户岗位列表项（不暴露阈值等内部配置）
 */
public record PublicJobDTO(
    Long id,
    String title,
    String department,
    String location,
    EmploymentType employmentType,
    Integer headcount,
    String salaryRange,
    InterviewMode interviewMode,
    LocalDateTime deadline,
    LocalDateTime publishedAt
) {}
