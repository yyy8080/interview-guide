package interview.guide.modules.recruitment.model;

/**
 * 在线面试防作弊事件类型（均为无感记录，不阻断作答）
 */
public enum ProctorEventType {
  /** 切换标签页 / 最小化（durationMs 为离开时长） */
  TAB_HIDDEN,
  /** 窗口失去焦点（durationMs 为失焦时长） */
  WINDOW_BLUR,
  /** 在作答框粘贴（chars 为粘贴字数） */
  PASTE,
  /** 复制题目 */
  COPY,
  /** 退出全屏（历史数据兼容，当前方案不强制全屏） */
  FULLSCREEN_EXIT,
  /** 浏览器窗口明显缩小（疑似分屏对照） */
  VIEWPORT_SHRINK,
  /** 检测到扩展显示器 */
  MULTI_SCREEN,
  /** 语音作答提交文本相对语音识别结果大幅改写（chars 为改写字数） */
  VOICE_TEXT_EDITED,
  /** 语音面试官提问结束后长时间未开口（durationMs 为应答延迟） */
  VOICE_SLOW_RESPONSE
}
