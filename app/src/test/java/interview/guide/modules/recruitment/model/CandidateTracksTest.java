package interview.guide.modules.recruitment.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.skill.InterviewSkillService.CategoryDTO;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("候选人技术方向偏好（前端/后端）工具类测试")
class CandidateTracksTest {

  @Test
  @DisplayName("normalizeOrNull 支持大小写规范化与空值处理，非法方向抛出 BAD_REQUEST")
  void normalizesAndValidatesTrack() {
    assertThat(CandidateTracks.normalizeOrNull(null)).isNull();
    assertThat(CandidateTracks.normalizeOrNull("   ")).isNull();
    assertThat(CandidateTracks.normalizeOrNull(" frontend ")).isEqualTo("FRONTEND");
    assertThat(CandidateTracks.normalizeOrNull("BACKEND")).isEqualTo("BACKEND");

    assertThatThrownBy(() -> CandidateTracks.normalizeOrNull("fullstack"))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.BAD_REQUEST.getCode());

    assertThat(CandidateTracks.safeNormalize("invalid")).isNull();
    assertThat(CandidateTracks.label("FRONTEND")).isEqualTo("偏向前端");
    assertThat(CandidateTracks.label("BACKEND")).isEqualTo("偏向后端");
  }

  @Test
  @DisplayName("resolveEffectiveSkillId 在岗位预设方向与候选人偏好冲突时自动切换对应方向")
  void resolvesEffectiveSkillIdByTrack() {
    assertThat(CandidateTracks.resolveEffectiveSkillId("java-backend", "FRONTEND")).isEqualTo("frontend");
    assertThat(CandidateTracks.resolveEffectiveSkillId("python-backend", "FRONTEND")).isEqualTo("frontend");
    assertThat(CandidateTracks.resolveEffectiveSkillId("ali-backend", "FRONTEND")).isEqualTo("frontend");
    assertThat(CandidateTracks.resolveEffectiveSkillId("frontend", "BACKEND")).isEqualTo("java-backend");

    // 通用或自定义 skill 保持原样，交由方向提示词与维度过滤协同生效
    assertThat(CandidateTracks.resolveEffectiveSkillId("custom", "FRONTEND")).isEqualTo("custom");
    assertThat(CandidateTracks.resolveEffectiveSkillId("ai-agent-dev", "FRONTEND")).isEqualTo("ai-agent-dev");
    assertThat(CandidateTracks.resolveEffectiveSkillId("java-backend", null)).isEqualTo("java-backend");
  }

  @Test
  @DisplayName("filterCategoriesForTrack 剔除相反方向的纯专属考察维度并保留通用或匹配维度")
  void filtersCategoriesForTrack() {
    List<CategoryDTO> mixed = List.of(
        new CategoryDTO("JAVA", "Java 与并发", null, "核心", true),
        new CategoryDTO("MYSQL", "MySQL 索引与事务", null, "重点", false),
        new CategoryDTO("REACT_VUE", "React / Vue 框架原理", null, "核心", true),
        new CategoryDTO("NETWORK", "HTTP 与计算机网络", null, "一般", false)
    );

    List<CategoryDTO> frontendFiltered = CandidateTracks.filterCategoriesForTrack(mixed, "FRONTEND");
    assertThat(frontendFiltered).extracting(CategoryDTO::key)
        .containsExactly("REACT_VUE", "NETWORK");

    List<CategoryDTO> backendFiltered = CandidateTracks.filterCategoriesForTrack(mixed, "BACKEND");
    assertThat(backendFiltered).extracting(CategoryDTO::key)
        .containsExactly("JAVA", "MYSQL", "NETWORK");
  }

  @Test
  @DisplayName("初筛、笔试、面试指导文案按所选方向包含对应技术侧重点")
  void buildsGuidancePrompts() {
    assertThat(CandidateTracks.screeningGuidance("FRONTEND")).contains("偏向前端").contains("React/Vue");
    assertThat(CandidateTracks.screeningGuidance("BACKEND")).contains("偏向后端").contains("MySQL");
    assertThat(CandidateTracks.writtenQuestionGuidance("FRONTEND")).contains("偏向前端").contains("不少于 80%");
    assertThat(CandidateTracks.writtenQuestionGuidance("BACKEND")).contains("偏向后端").contains("不少于 80%");
    assertThat(CandidateTracks.interviewGuidance("FRONTEND")).contains("偏向前端");
    assertThat(CandidateTracks.interviewGuidance("BACKEND")).contains("偏向后端");
    assertThat(CandidateTracks.interviewGuidance(null)).isEmpty();
  }
}
