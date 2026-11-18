package interview.guide.modules.recruitment.model;

/**
 * 在线面试作弊风险等级（仅作为 HR 复核参考，不自动淘汰）
 */
public enum ProctorRiskLevel {
  /** 行为正常 */
  LOW,
  /** 存在可疑行为，建议关注 */
  MEDIUM,
  /** 多项强信号叠加，建议重点复核 / 线下追问 */
  HIGH
}
