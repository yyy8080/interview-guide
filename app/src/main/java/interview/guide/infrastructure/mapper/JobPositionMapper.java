package interview.guide.infrastructure.mapper;

import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import interview.guide.modules.job.model.JobPositionDTO;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.model.JobPositionRequest;
import interview.guide.modules.job.model.PublicJobDTO;
import interview.guide.modules.job.model.PublicJobDetailDTO;
import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.ReportingPolicy;

/**
 * 招聘岗位对象映射
 * <p>
 * categoriesJson 的 JSON 序列化在 Service 层处理，这里通过额外参数传入解析后的列表。
 */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.IGNORE)
public interface JobPositionMapper {

  @Mapping(target = "categories", source = "categories")
  JobPositionDTO toDTO(JobPositionEntity entity, List<CategoryDTO> categories);

  PublicJobDTO toPublicDTO(JobPositionEntity entity);

  PublicJobDetailDTO toPublicDetailDTO(JobPositionEntity entity);

  /**
   * 用请求覆盖可编辑字段（状态、审计字段与 categoriesJson 由 Service 维护）
   */
  @Mapping(target = "id", ignore = true)
  @Mapping(target = "status", ignore = true)
  @Mapping(target = "categoriesJson", ignore = true)
  @Mapping(target = "publishedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "updatedAt", ignore = true)
  @Mapping(target = "screeningEnabled", source = "screeningEnabled")
  void updateEntity(JobPositionRequest request, @MappingTarget JobPositionEntity entity);
}
