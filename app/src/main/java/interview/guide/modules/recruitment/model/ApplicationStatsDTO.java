package interview.guide.modules.recruitment.model;

import java.util.List;
import java.util.Map;

/**
 * 招聘漏斗统计
 *
 * @param byStatus 各状态当前数量
 * @param funnel   漏斗各阶段累计到达数量（按流程顺序）
 */
public record ApplicationStatsDTO(long total, Map<ApplicationStatus, Long> byStatus, List<FunnelStage> funnel) {

  public record FunnelStage(String key, String label, long count) {
  }
}
