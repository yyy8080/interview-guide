package interview.guide.modules.recruitment.service;

import interview.guide.modules.recruitment.model.AnswerTelemetryEntity;
import interview.guide.modules.recruitment.model.ProctorEventEntity;
import interview.guide.modules.recruitment.model.ProctorEventType;
import interview.guide.modules.recruitment.model.ProctorRiskAssessment;
import interview.guide.modules.recruitment.model.ProctorRiskLevel;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 作弊风险评分引擎：把无感采集的行为事件与逐题作答遥测聚合为 0-100 风险分。
 * <p>
 * 设计原则：短暂离开（&lt; 5 秒，如关闭系统通知）不计分；单一弱信号只会把分数推到“中风险”，
 * 只有多项强信号叠加才会达到“高风险”。结果仅供 AI 决策与 HR 复核参考，不自动淘汰。
 */
@Component
public class ProctorRiskCalculator {

  /** 低于该时长的离开视为正常误触，不计分 */
  static final long SHORT_AWAY_MS = 5_000;
  /** 超过该时长视为长时间离开 */
  static final long LONG_AWAY_MS = 30_000;
  static final int MID_AWAY_POINTS = 3;
  static final int LONG_AWAY_POINTS = 8;
  /** 无时长的历史离开事件 */
  static final int LEGACY_AWAY_POINTS = 2;
  static final int AWAY_POINTS_CAP = 40;

  static final int COPY_POINTS = 3;
  static final int COPY_POINTS_CAP = 9;
  static final int SCREEN_POINTS = 5;

  /** 简答题大段粘贴：粘贴字数与占比阈值 */
  static final int LARGE_PASTE_CHARS = 100;
  static final double LARGE_PASTE_RATIO = 0.7;
  static final int LARGE_PASTE_POINTS = 15;
  /** 作答期间离开超过该时长后又大段粘贴，视为强信号 */
  static final long AWAY_BEFORE_PASTE_MS = 15_000;
  static final int AWAY_AND_PASTE_EXTRA_POINTS = 10;
  /** 非粘贴情况下 1 秒内新增字数阈值（正常输入法整句上屏通常不超过 30 字） */
  static final int BURST_CHARS = 60;
  static final int BURST_POINTS = 10;

  static final int VOICE_EDIT_POINTS = 10;
  static final int VOICE_EDIT_POINTS_CAP = 30;
  static final int VOICE_SLOW_POINTS = 2;
  static final int VOICE_SLOW_POINTS_CAP = 10;
  /** 应答延迟次数达到该值才写入风险说明 */
  static final int VOICE_SLOW_FINDING_MIN = 3;

  static final int MEDIUM_THRESHOLD = 25;
  static final int HIGH_THRESHOLD = 50;

  private record Finding(int weight, String text) {
  }

  public ProctorRiskAssessment assess(List<ProctorEventEntity> events, List<AnswerTelemetryEntity> telemetry) {
    List<ProctorEventEntity> safeEvents = events == null ? List.of() : events;
    List<AnswerTelemetryEntity> safeTelemetry = telemetry == null ? List.of() : telemetry;
    List<Finding> findings = new ArrayList<>();
    TreeSet<Integer> flagged = new TreeSet<>();
    int score = 0;

    score += scoreAway(safeEvents, findings, flagged);
    score += scoreOtherEvents(safeEvents, findings);
    score += scoreTelemetry(safeTelemetry, findings, flagged);

    int finalScore = Math.min(100, Math.max(0, score));
    List<String> texts = findings.stream()
        .sorted(Comparator.comparingInt(Finding::weight).reversed())
        .map(Finding::text)
        .toList();
    return new ProctorRiskAssessment(finalScore, levelOf(finalScore), texts, List.copyOf(flagged));
  }

  static ProctorRiskLevel levelOf(int score) {
    if (score >= HIGH_THRESHOLD) {
      return ProctorRiskLevel.HIGH;
    }
    if (score >= MEDIUM_THRESHOLD) {
      return ProctorRiskLevel.MEDIUM;
    }
    return ProctorRiskLevel.LOW;
  }

  private int scoreAway(List<ProctorEventEntity> events, List<Finding> findings, TreeSet<Integer> flagged) {
    int mid = 0;
    int longCount = 0;
    int legacy = 0;
    long longest = 0;
    for (ProctorEventEntity e : events) {
      if (e.getEventType() != ProctorEventType.TAB_HIDDEN && e.getEventType() != ProctorEventType.WINDOW_BLUR) {
        continue;
      }
      Long duration = e.getDurationMs();
      if (duration == null) {
        legacy++;
        continue;
      }
      longest = Math.max(longest, duration);
      if (duration >= LONG_AWAY_MS) {
        longCount++;
        if (e.getQuestionIndex() != null) {
          flagged.add(e.getQuestionIndex());
        }
      } else if (duration >= SHORT_AWAY_MS) {
        mid++;
      }
    }
    int points = Math.min(AWAY_POINTS_CAP,
        mid * MID_AWAY_POINTS + longCount * LONG_AWAY_POINTS + legacy * LEGACY_AWAY_POINTS);
    if (longCount > 0) {
      findings.add(new Finding(longCount * LONG_AWAY_POINTS,
          "离开页面超过 30 秒 " + longCount + " 次（最长 " + formatDuration(longest) + "）"));
    }
    if (mid > 0) {
      findings.add(new Finding(mid * MID_AWAY_POINTS, "离开页面 5-30 秒 " + mid + " 次"));
    }
    if (legacy > 0) {
      findings.add(new Finding(legacy * LEGACY_AWAY_POINTS, "切换页面 / 窗口失焦 " + legacy + " 次（未记录时长）"));
    }
    return points;
  }

  private int scoreOtherEvents(List<ProctorEventEntity> events, List<Finding> findings) {
    int copy = 0;
    int fullscreen = 0;
    boolean shrink = false;
    boolean multiScreen = false;
    int voiceEdits = 0;
    int voiceEditChars = 0;
    int voiceSlow = 0;
    for (ProctorEventEntity e : events) {
      switch (e.getEventType()) {
        case COPY -> copy++;
        case FULLSCREEN_EXIT -> fullscreen++;
        case VIEWPORT_SHRINK -> shrink = true;
        case MULTI_SCREEN -> multiScreen = true;
        case VOICE_TEXT_EDITED -> {
          voiceEdits++;
          voiceEditChars += e.getChars() == null ? 0 : e.getChars();
        }
        case VOICE_SLOW_RESPONSE -> voiceSlow++;
        default -> {
          // 离开类事件单独计分；粘贴以逐题遥测为准，避免重复计分
        }
      }
    }
    int points = 0;
    if (copy > 0) {
      int p = Math.min(COPY_POINTS_CAP, copy * COPY_POINTS);
      points += p;
      findings.add(new Finding(p, "尝试复制题目 " + copy + " 次"));
    }
    if (fullscreen > 0) {
      int p = fullscreen * LEGACY_AWAY_POINTS;
      points += p;
      findings.add(new Finding(p, "退出全屏 " + fullscreen + " 次"));
    }
    if (shrink) {
      points += SCREEN_POINTS;
      findings.add(new Finding(SCREEN_POINTS, "作答时浏览器窗口明显缩小（疑似分屏对照）"));
    }
    if (multiScreen) {
      points += SCREEN_POINTS;
      findings.add(new Finding(SCREEN_POINTS, "检测到扩展显示器"));
    }
    if (voiceEdits > 0) {
      int p = Math.min(VOICE_EDIT_POINTS_CAP, voiceEdits * VOICE_EDIT_POINTS);
      points += p;
      findings.add(new Finding(p, "语音回答提交前大幅改写识别文本 " + voiceEdits + " 次（共改写约 " + voiceEditChars + " 字）"));
    }
    if (voiceSlow > 0) {
      int p = Math.min(VOICE_SLOW_POINTS_CAP, voiceSlow * VOICE_SLOW_POINTS);
      points += p;
      if (voiceSlow >= VOICE_SLOW_FINDING_MIN) {
        findings.add(new Finding(p, "面试官提问后长时间未开口 " + voiceSlow + " 次"));
      }
    }
    return points;
  }

  private int scoreTelemetry(List<AnswerTelemetryEntity> telemetry, List<Finding> findings,
                             TreeSet<Integer> flagged) {
    int points = 0;
    for (AnswerTelemetryEntity t : telemetry) {
      int pasted = nz(t.getPastedChars());
      int typed = nz(t.getTypedChars());
      long away = t.getAwayMs() == null ? 0 : t.getAwayMs();
      int questionNo = t.getQuestionIndex() + 1;
      Double ratio = pasteRatio(typed, pasted);
      boolean largePaste = pasted >= LARGE_PASTE_CHARS && ratio != null && ratio >= LARGE_PASTE_RATIO;
      if (largePaste) {
        int p = LARGE_PASTE_POINTS;
        String text = "第 " + questionNo + " 题粘贴 " + pasted + " 字（占 " + Math.round(ratio * 100) + "%）";
        if (away >= AWAY_BEFORE_PASTE_MS) {
          p += AWAY_AND_PASTE_EXTRA_POINTS;
          text += "，作答期间离开 " + formatDuration(away);
        }
        points += p;
        flagged.add(t.getQuestionIndex());
        findings.add(new Finding(p, text));
      } else if (nz(t.getMaxBurstChars()) >= BURST_CHARS) {
        points += BURST_POINTS;
        flagged.add(t.getQuestionIndex());
        findings.add(new Finding(BURST_POINTS,
            "第 " + questionNo + " 题 1 秒内新增 " + t.getMaxBurstChars() + " 字（非粘贴输入，疑似外部注入）"));
      }
    }
    return points;
  }

  /** 粘贴占比；无输入时返回 null */
  public static Double pasteRatio(Integer typedChars, Integer pastedChars) {
    int typed = nz(typedChars);
    int pasted = nz(pastedChars);
    if (typed + pasted == 0) {
      return null;
    }
    return (double) pasted / (typed + pasted);
  }

  static String formatDuration(long ms) {
    long seconds = Math.max(0, ms / 1000);
    if (seconds < 60) {
      return seconds + " 秒";
    }
    long minutes = seconds / 60;
    long rest = seconds % 60;
    return rest == 0 ? minutes + " 分钟" : minutes + " 分 " + rest + " 秒";
  }

  private static int nz(Integer value) {
    return value == null ? 0 : value;
  }
}
