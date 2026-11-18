package interview.guide.modules.job.model;

/**
 * 在线面试形式
 */
public enum InterviewMode {
  /** 文字问答面试 */
  TEXT,
  /** 实时语音面试（需配置 ASR/TTS） */
  VOICE,
  /** 候选人开始面试时自选文字或语音 */
  BOTH;

  /** 候选人是否可以使用该形式（BOTH 表示两者皆可） */
  public boolean allows(InterviewMode chosen) {
    return this == BOTH ? chosen != BOTH : this == chosen;
  }
}
