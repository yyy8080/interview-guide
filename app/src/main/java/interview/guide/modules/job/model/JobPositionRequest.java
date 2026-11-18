package interview.guide.modules.job.model;

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建 / 更新岗位请求
 */
public record JobPositionRequest(
    @NotBlank(message = "岗位名称不能为空")
    @Size(max = 128, message = "岗位名称不能超过128字")
    String title,

    @Size(max = 128, message = "部门名称不能超过128字")
    String department,

    @Size(max = 128, message = "工作地点不能超过128字")
    String location,

    @NotNull(message = "用工类型不能为空")
    EmploymentType employmentType,

    @Min(value = 1, message = "招聘人数至少为1")
    @Max(value = 9999, message = "招聘人数过大")
    Integer headcount,

    @Size(max = 64, message = "薪资范围不能超过64字")
    String salaryRange,

    @NotBlank(message = "职位描述（JD）不能为空")
    @Size(max = 20000, message = "职位描述不能超过20000字")
    String jdText,

    /** JD 解析出的考察维度（skillId = custom 时发布前必填） */
    @Size(max = 20, message = "考察维度最多20个")
    List<@Valid CategoryDTO> categories,

    @NotBlank(message = "面试主题不能为空")
    @Size(max = 64, message = "面试主题ID过长")
    String skillId,

    @NotBlank(message = "面试难度不能为空")
    @Pattern(regexp = "junior|mid|senior", message = "难度只能是 junior / mid / senior")
    String difficulty,

    @NotNull(message = "题目数量不能为空")
    @Min(value = 3, message = "题目数量最少3题")
    @Max(value = 20, message = "题目数量最多20题")
    Integer questionCount,

    @NotNull(message = "面试形式不能为空")
    InterviewMode interviewMode,

    Long knowledgeBaseId,

    @Min(value = 5, message = "面试时长至少5分钟")
    @Max(value = 180, message = "面试时长最多180分钟")
    Integer timeLimitMinutes,

    @Size(max = 50, message = "模型提供商ID过长")
    String llmProvider,

    @NotNull(message = "是否启用简历初筛不能为空")
    Boolean screeningEnabled,

    @Min(value = 0, message = "初筛分数线范围 0-100")
    @Max(value = 100, message = "初筛分数线范围 0-100")
    Integer screeningThreshold,

    @NotNull(message = "AI 面试通过分数线不能为空")
    @Min(value = 0, message = "通过分数线范围 0-100")
    @Max(value = 100, message = "通过分数线范围 0-100")
    Integer aiPassThreshold,

    LocalDateTime deadline,

    /** 长时间离开提醒，缺省开启 */
    Boolean proctorAwayReminder,

    /** 轻量水印，缺省开启 */
    Boolean proctorWatermark,

    /** 是否启用在线笔试（选择题 / 填空题），缺省关闭 */
    Boolean writtenTestEnabled,

    @Min(value = 0, message = "单选题数量范围 0-20")
    @Max(value = 20, message = "单选题数量范围 0-20")
    Integer writtenSingleCount,

    @Min(value = 0, message = "多选题数量范围 0-20")
    @Max(value = 20, message = "多选题数量范围 0-20")
    Integer writtenMultiCount,

    @Min(value = 0, message = "填空题数量范围 0-20")
    @Max(value = 20, message = "填空题数量范围 0-20")
    Integer writtenFillCount,

    @Min(value = 5, message = "笔试时长至少5分钟")
    @Max(value = 120, message = "笔试时长最多120分钟")
    Integer writtenTimeLimitMinutes,

    @Min(value = 0, message = "笔试权重范围 0-100")
    @Max(value = 100, message = "笔试权重范围 0-100")
    Integer writtenWeight
) {

  public JobPositionRequest(String title, String department, String location, EmploymentType employmentType,
                            Integer headcount, String salaryRange, String jdText, List<CategoryDTO> categories,
                            String skillId, String difficulty, Integer questionCount, InterviewMode interviewMode,
                            Long knowledgeBaseId, Integer timeLimitMinutes, String llmProvider,
                            Boolean screeningEnabled, Integer screeningThreshold, Integer aiPassThreshold,
                            LocalDateTime deadline) {
    this(title, department, location, employmentType, headcount, salaryRange, jdText, categories, skillId,
        difficulty, questionCount, interviewMode, knowledgeBaseId, timeLimitMinutes, llmProvider, screeningEnabled,
        screeningThreshold, aiPassThreshold, deadline, null, null, null, null, null, null, null, null);
  }
}

