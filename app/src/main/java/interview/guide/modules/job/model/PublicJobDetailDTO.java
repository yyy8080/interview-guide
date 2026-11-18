package interview.guide.modules.job.model;

import java.time.LocalDateTime;

/**
 * 候选人门户岗位详情（含 JD 与面试须知，不暴露阈值等内部配置）
 */
public record PublicJobDetailDTO(
    Long id,
    String title,
    String department,
    String location,
    EmploymentType employmentType,
    Integer headcount,
    String salaryRange,
    String jdText,
    InterviewMode interviewMode,
    Integer questionCount,
    Integer timeLimitMinutes,
    LocalDateTime deadline,
    LocalDateTime publishedAt
) {}
