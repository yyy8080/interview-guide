package interview.guide.modules.recruitment.listener;

import interview.guide.common.event.VoiceAnswerSubmittedEvent;
import interview.guide.modules.recruitment.model.ProctorEventRequest;
import interview.guide.modules.recruitment.model.ProctorEventType;
import interview.guide.modules.recruitment.service.CandidateInterviewService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 语音作答改写检测：候选人可以修正识别错别字，但若提交文本相对语音识别结果被大幅改写，
 * 记录为 VOICE_TEXT_EDITED 行为事件，供风险评分与 HR 复核参考（不阻断作答）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VoiceAnswerProctorListener {

  /** 改写比例阈值：编辑距离 / 较长文本长度 */
  static final double EDIT_RATIO_THRESHOLD = 0.3;
  /** 改写字数阈值，避免短句修正几个字就触发 */
  static final int EDIT_CHARS_THRESHOLD = 10;
  /** 编辑距离计算的最大文本长度 */
  static final int MAX_COMPARE_CHARS = 2000;

  private final CandidateInterviewService interviewService;

  @EventListener
  public void onVoiceAnswerSubmitted(VoiceAnswerSubmittedEvent event) {
    try {
      Long voiceSessionId = Long.parseLong(event.sessionId());
      String recognized = truncate(event.recognizedText());
      String submitted = truncate(event.submittedText());
      int distance = editDistance(recognized, submitted);
      int base = Math.max(1, Math.max(recognized.length(), submitted.length()));
      double ratio = (double) distance / base;
      if (ratio < EDIT_RATIO_THRESHOLD || distance < EDIT_CHARS_THRESHOLD) {
        return;
      }
      String detail = "识别 " + recognized.length() + " 字 → 提交 " + submitted.length() + " 字，改写约 "
          + Math.round(ratio * 100) + "%";
      interviewService.recordVoiceProctor(voiceSessionId,
          new ProctorEventRequest(ProctorEventType.VOICE_TEXT_EDITED, detail, null, null, distance));
    } catch (NumberFormatException e) {
      // 非数字会话 ID 不属于招聘语音面试
    } catch (Exception e) {
      log.warn("记录语音作答改写失败: sessionId={}, error={}", event.sessionId(), e.getMessage());
    }
  }

  private static String truncate(String text) {
    String s = text == null ? "" : text.trim();
    return s.length() > MAX_COMPARE_CHARS ? s.substring(0, MAX_COMPARE_CHARS) : s;
  }

  /** Levenshtein 编辑距离（两行滚动数组） */
  static int editDistance(String a, String b) {
    int n = a.length();
    int m = b.length();
    if (n == 0) {
      return m;
    }
    if (m == 0) {
      return n;
    }
    int[] prev = new int[m + 1];
    int[] curr = new int[m + 1];
    for (int j = 0; j <= m; j++) {
      prev[j] = j;
    }
    for (int i = 1; i <= n; i++) {
      curr[0] = i;
      char ca = a.charAt(i - 1);
      for (int j = 1; j <= m; j++) {
        int cost = ca == b.charAt(j - 1) ? 0 : 1;
        curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
      }
      int[] tmp = prev;
      prev = curr;
      curr = tmp;
    }
    return prev[m];
  }
}
