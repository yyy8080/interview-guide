package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.recruitment.model.AnswerTelemetryEntity;
import interview.guide.modules.recruitment.model.ProctorEventEntity;
import interview.guide.modules.recruitment.model.ProctorEventType;
import interview.guide.modules.recruitment.model.ProctorRiskAssessment;
import interview.guide.modules.recruitment.model.ProctorRiskLevel;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProctorRiskCalculatorTest {

  private final ProctorRiskCalculator calculator = new ProctorRiskCalculator();

  @Test
  @DisplayName("短暂离开（<5 秒）不计分，空数据为低风险")
  void shortAwayIsIgnored() {
    ProctorRiskAssessment empty = calculator.assess(null, null);
    assertThat(empty.score()).isZero();
    assertThat(empty.level()).isEqualTo(ProctorRiskLevel.LOW);

    ProctorRiskAssessment result = calculator.assess(
        List.of(away(ProctorEventType.TAB_HIDDEN, 2_000L, 0), away(ProctorEventType.WINDOW_BLUR, 4_999L, 1)),
        List.of());
    assertThat(result.score()).isZero();
    assertThat(result.findings()).isEmpty();
    assertThat(result.flaggedQuestions()).isEmpty();
  }

  @Test
  @DisplayName("长时间离开后大段粘贴叠加为高风险，并标记对应题目")
  void longAwayPlusLargePasteIsHigh() {
    List<ProctorEventEntity> events = List.of(
        away(ProctorEventType.TAB_HIDDEN, 45_000L, 2),
        away(ProctorEventType.TAB_HIDDEN, 60_000L, 3),
        away(ProctorEventType.WINDOW_BLUR, 10_000L, 1));
    List<AnswerTelemetryEntity> telemetry = List.of(
        telemetry(2, 10, 300, 0, 40_000L),
        telemetry(3, 5, 200, 0, 0L));

    ProctorRiskAssessment result = calculator.assess(events, telemetry);

    // 离开：2×8 + 1×3 = 19；粘贴：第 3 题 15+10，第 4 题 15 → 59
    assertThat(result.score()).isEqualTo(59);
    assertThat(result.level()).isEqualTo(ProctorRiskLevel.HIGH);
    assertThat(result.flaggedQuestions()).containsExactly(2, 3);
    // 权重最高的“离开后粘贴”排在最前
    assertThat(result.findings().get(0)).contains("第 3 题粘贴 300 字").contains("离开");
  }

  @Test
  @DisplayName("非粘贴的输入突增单独计分并标记，单一弱信号不会进入高风险")
  void burstInputIsFlagged() {
    ProctorRiskAssessment result = calculator.assess(List.of(), List.of(telemetry(0, 120, 0, 80, 0L)));
    assertThat(result.score()).isEqualTo(ProctorRiskCalculator.BURST_POINTS);
    assertThat(result.level()).isEqualTo(ProctorRiskLevel.LOW);
    assertThat(result.flaggedQuestions()).containsExactly(0);
    assertThat(result.findings()).singleElement().asString().contains("1 秒内新增 80 字");
  }

  @Test
  @DisplayName("少量粘贴（占比低）不计分；语音改写与离开次数均有上限")
  void capsAndSmallPaste() {
    ProctorRiskAssessment smallPaste = calculator.assess(List.of(), List.of(telemetry(0, 400, 150, 0, 0L)));
    assertThat(smallPaste.score()).isZero();

    List<ProctorEventEntity> edits = List.of(
        voiceEdit(20), voiceEdit(30), voiceEdit(40), voiceEdit(50), voiceEdit(60));
    ProctorRiskAssessment voice = calculator.assess(edits, List.of());
    assertThat(voice.score()).isEqualTo(ProctorRiskCalculator.VOICE_EDIT_POINTS_CAP);
    assertThat(voice.level()).isEqualTo(ProctorRiskLevel.MEDIUM);
    assertThat(voice.findings()).singleElement().asString().contains("5 次").contains("200 字");

    List<ProctorEventEntity> manyAways = java.util.stream.IntStream.range(0, 20)
        .mapToObj(i -> away(ProctorEventType.TAB_HIDDEN, 40_000L, i))
        .toList();
    assertThat(calculator.assess(manyAways, List.of()).score())
        .isEqualTo(ProctorRiskCalculator.AWAY_POINTS_CAP);
  }

  @Test
  @DisplayName("语音应答延迟少于 3 次只计分不写说明；历史无时长事件按低权重计分")
  void voiceSlowAndLegacy() {
    ProctorRiskAssessment result = calculator.assess(List.of(
        event(ProctorEventType.VOICE_SLOW_RESPONSE),
        event(ProctorEventType.VOICE_SLOW_RESPONSE),
        away(ProctorEventType.TAB_HIDDEN, null, null)), List.of());
    assertThat(result.score()).isEqualTo(2 * ProctorRiskCalculator.VOICE_SLOW_POINTS
        + ProctorRiskCalculator.LEGACY_AWAY_POINTS);
    assertThat(result.findings()).singleElement().asString().contains("未记录时长");
  }

  @Test
  @DisplayName("分级阈值与时长格式化")
  void levelsAndFormatting() {
    assertThat(ProctorRiskCalculator.levelOf(24)).isEqualTo(ProctorRiskLevel.LOW);
    assertThat(ProctorRiskCalculator.levelOf(25)).isEqualTo(ProctorRiskLevel.MEDIUM);
    assertThat(ProctorRiskCalculator.levelOf(50)).isEqualTo(ProctorRiskLevel.HIGH);
    assertThat(ProctorRiskCalculator.formatDuration(45_000)).isEqualTo("45 秒");
    assertThat(ProctorRiskCalculator.formatDuration(120_000)).isEqualTo("2 分钟");
    assertThat(ProctorRiskCalculator.formatDuration(95_000)).isEqualTo("1 分 35 秒");
    assertThat(ProctorRiskCalculator.pasteRatio(0, 0)).isNull();
    assertThat(ProctorRiskCalculator.pasteRatio(30, 70)).isEqualTo(0.7);
  }

  private static ProctorEventEntity event(ProctorEventType type) {
    ProctorEventEntity e = new ProctorEventEntity();
    e.setApplicationId(1L);
    e.setEventType(type);
    return e;
  }

  private static ProctorEventEntity away(ProctorEventType type, Long durationMs, Integer questionIndex) {
    ProctorEventEntity e = event(type);
    e.setDurationMs(durationMs);
    e.setQuestionIndex(questionIndex);
    return e;
  }

  private static ProctorEventEntity voiceEdit(int chars) {
    ProctorEventEntity e = event(ProctorEventType.VOICE_TEXT_EDITED);
    e.setChars(chars);
    return e;
  }

  private static AnswerTelemetryEntity telemetry(int questionIndex, int typed, int pasted, int burst, Long awayMs) {
    AnswerTelemetryEntity t = new AnswerTelemetryEntity();
    t.setApplicationId(1L);
    t.setQuestionIndex(questionIndex);
    t.setTypedChars(typed);
    t.setPastedChars(pasted);
    t.setMaxBurstChars(burst);
    t.setAwayMs(awayMs);
    return t;
  }
}
