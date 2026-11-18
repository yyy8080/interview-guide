package interview.guide.modules.recruitment.model;

/**
 * 在线面试防作弊事件类型
 */
public enum ProctorEventType {
  /** 切换标签页 / 最小化 */
  TAB_HIDDEN,
  /** 窗口失去焦点 */
  WINDOW_BLUR,
  /** 在答题框粘贴 */
  PASTE,
  /** 复制题目 */
  COPY,
  /** 退出全屏 */
  FULLSCREEN_EXIT
}
