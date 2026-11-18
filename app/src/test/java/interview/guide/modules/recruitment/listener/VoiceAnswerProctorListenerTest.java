package interview.guide.modules.recruitment.listener;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import interview.guide.common.event.VoiceAnswerSubmittedEvent;
import interview.guide.modules.recruitment.model.ProctorEventRequest;
import interview.guide.modules.recruitment.model.ProctorEventType;
import interview.guide.modules.recruitment.service.CandidateInterviewService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VoiceAnswerProctorListenerTest {

  @Mock
  private CandidateInterviewService interviewService;

  @InjectMocks
  private VoiceAnswerProctorListener listener;

  @Test
  @DisplayName("编辑距离计算正确")
  void editDistance() {
    assertThat(VoiceAnswerProctorListener.editDistance("", "abc")).isEqualTo(3);
    assertThat(VoiceAnswerProctorListener.editDistance("kitten", "sitting")).isEqualTo(3);
    assertThat(VoiceAnswerProctorListener.editDistance("一致", "一致")).isZero();
  }

  @Test
  @DisplayName("只修正少量错别字不记录")
  void minorCorrectionIgnored() {
    listener.onVoiceAnswerSubmitted(new VoiceAnswerSubmittedEvent(
        "12", "我认为 Redis 的持久化有 RDB 和 AOF 两种方式", "我认为 Redis 的持久化有 RDB 和 AOF 两种方式。"));
    verify(interviewService, never()).recordVoiceProctor(anyLong(), any());
  }

  @Test
  @DisplayName("大幅改写识别文本记录为 VOICE_TEXT_EDITED")
  void heavyRewriteRecorded() {
    String recognized = "嗯这个我不太清楚";
    String submitted = "Redis 持久化分为 RDB 快照与 AOF 追加日志，生产环境通常混合使用以兼顾性能与可靠性。";
    listener.onVoiceAnswerSubmitted(new VoiceAnswerSubmittedEvent("12", recognized, submitted));

    ArgumentCaptor<ProctorEventRequest> captor = ArgumentCaptor.forClass(ProctorEventRequest.class);
    verify(interviewService).recordVoiceProctor(eq(12L), captor.capture());
    ProctorEventRequest request = captor.getValue();
    assertThat(request.type()).isEqualTo(ProctorEventType.VOICE_TEXT_EDITED);
    assertThat(request.chars()).isGreaterThanOrEqualTo(VoiceAnswerProctorListener.EDIT_CHARS_THRESHOLD);
    assertThat(request.detail()).contains("改写约");
  }

  @Test
  @DisplayName("非招聘语音会话（非数字 ID）忽略")
  void nonNumericSessionIgnored() {
    listener.onVoiceAnswerSubmitted(new VoiceAnswerSubmittedEvent("abc", "", "完全不同的一大段文本内容用于测试"));
    verify(interviewService, never()).recordVoiceProctor(anyLong(), any());
  }
}
