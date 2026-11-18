package interview.guide.modules.auth.model;

/**
 * HR 后台角色。
 * <ul>
 *   <li>ADMIN：系统管理员，可管理账号、模型配置</li>
 *   <li>HR：招聘专员，可管理岗位、审核候选人、安排面试</li>
 *   <li>INTERVIEWER：面试官，查看分配给自己的候选人（后续阶段细化）</li>
 * </ul>
 */
public enum AdminRole {
  ADMIN,
  HR,
  INTERVIEWER
}
