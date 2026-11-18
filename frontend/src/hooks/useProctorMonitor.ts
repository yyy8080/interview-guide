import { useCallback, useEffect, useRef, useState } from 'react';
import { candidateApi } from '../api/candidate';
import type { ProctorEventPayload, ProctorEventType } from '../types/recruitment';
import { isViewportShrunk } from '../utils/proctorTelemetry';

/** 低于该时长的离开不上报（如系统通知弹窗、误触） */
const MIN_AWAY_REPORT_MS = 1_000;
/** 离开超过该时长回来后给出柔和提醒 */
const REMINDER_AWAY_MS = 30_000;
/** 单场面试最多提醒次数 */
const MAX_REMINDERS = 2;
const REMINDER_VISIBLE_MS = 6_000;
/** 同类无时长事件的上报去重间隔 */
const DEDUPE_MS = 1_500;
const MAX_SHRINK_REPORTS = 5;

interface Options {
  applicationId: number;
  enabled: boolean;
  /** 是否在长时间离开后提醒（岗位配置） */
  awayReminder: boolean;
  /** 当前正在作答的题号（文字面试） */
  getQuestionIndex?: () => number | undefined;
  /** 每次离开结束时回调离开时长，用于累计到当前题目 */
  onAway?: (ms: number) => void;
}

function isTypingTarget(target: EventTarget | null): boolean {
  if (!(target instanceof HTMLElement)) return false;
  return target.closest('textarea, input, [contenteditable="true"]') != null;
}

/**
 * 在线面试无感行为采集：离开时长、复制题目、窗口明显缩小、扩展屏。
 * 不弹窗、不阻断；仅在长时间离开后给出一次性的柔和提醒（可由岗位关闭）。
 */
export function useProctorMonitor({ applicationId, enabled, awayReminder, getQuestionIndex, onAway }: Options) {
  const [reminder, setReminder] = useState<string | null>(null);
  const lastReportRef = useRef<Partial<Record<ProctorEventType, number>>>({});
  const remindersRef = useRef(0);
  const getQuestionIndexRef = useRef(getQuestionIndex);
  const onAwayRef = useRef(onAway);
  const awayReminderRef = useRef(awayReminder);

  useEffect(() => {
    getQuestionIndexRef.current = getQuestionIndex;
    onAwayRef.current = onAway;
    awayReminderRef.current = awayReminder;
  }, [getQuestionIndex, onAway, awayReminder]);

  const report = useCallback(
    (event: ProctorEventPayload, dedupe = false) => {
      if (!applicationId || Number.isNaN(applicationId)) return;
      if (dedupe) {
        const now = Date.now();
        if (now - (lastReportRef.current[event.type] ?? 0) < DEDUPE_MS) return;
        lastReportRef.current[event.type] = now;
      }
      const questionIndex = event.questionIndex ?? getQuestionIndexRef.current?.();
      candidateApi
        .recordProctor(applicationId, questionIndex == null ? event : { ...event, questionIndex })
        .catch(() => {});
    },
    [applicationId],
  );

  const dismissReminder = useCallback(() => setReminder(null), []);

  useEffect(() => {
    if (!enabled) return;

    let hiddenAt: number | null = null;
    let blurAt: number | null = null;
    let hiddenDuringBlur = false;
    let reminderTimer: number | undefined;

    const handleAway = (type: ProctorEventType, ms: number) => {
      if (ms < MIN_AWAY_REPORT_MS) return;
      onAwayRef.current?.(ms);
      report({ type, durationMs: ms });
      if (awayReminderRef.current && ms >= REMINDER_AWAY_MS && remindersRef.current < MAX_REMINDERS) {
        remindersRef.current += 1;
        setReminder('检测到离开页面较久，请专注作答哦');
        window.clearTimeout(reminderTimer);
        reminderTimer = window.setTimeout(() => setReminder(null), REMINDER_VISIBLE_MS);
      }
    };

    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        hiddenAt = Date.now();
        hiddenDuringBlur = true;
      } else if (hiddenAt != null) {
        const ms = Date.now() - hiddenAt;
        hiddenAt = null;
        handleAway('TAB_HIDDEN', ms);
      }
    };
    const onBlur = () => {
      blurAt = Date.now();
      hiddenDuringBlur = document.visibilityState === 'hidden';
    };
    const onFocus = () => {
      if (blurAt == null) return;
      const ms = Date.now() - blurAt;
      blurAt = null;
      // 切换标签页时 blur 与 visibilitychange 同时发生，只按切换标签页记录一次
      if (!hiddenDuringBlur) handleAway('WINDOW_BLUR', ms);
      hiddenDuringBlur = false;
    };
    const onCopy = (e: ClipboardEvent) => {
      // 复制自己的作答内容属于正常操作
      if (isTypingTarget(e.target)) return;
      report({ type: 'COPY' }, true);
    };

    // 窗口相对开考时明显缩小（触屏设备软键盘会改变视口高度，不做检测）
    const coarsePointer = window.matchMedia?.('(pointer: coarse)').matches ?? false;
    const baseline = { width: window.innerWidth, height: window.innerHeight };
    let shrunk = false;
    let shrinkReports = 0;
    let resizeTimer: number | undefined;
    const onResize = () => {
      if (coarsePointer) return;
      window.clearTimeout(resizeTimer);
      resizeTimer = window.setTimeout(() => {
        const current = { width: window.innerWidth, height: window.innerHeight };
        const nowShrunk = isViewportShrunk(baseline, current);
        if (nowShrunk && !shrunk && shrinkReports < MAX_SHRINK_REPORTS) {
          shrinkReports += 1;
          report({
            type: 'VIEWPORT_SHRINK',
            detail: `窗口 ${current.width}×${current.height}（开始时 ${baseline.width}×${baseline.height}）`,
          });
        }
        shrunk = nowShrunk;
      }, 1_000);
    };

    // 扩展显示器（Chromium 支持，无需授权；仅记录，不拦截）
    let multiScreenReported = false;
    const checkScreens = () => {
      const extended = (window.screen as Screen & { isExtended?: boolean }).isExtended;
      if (extended === true && !multiScreenReported) {
        multiScreenReported = true;
        report({ type: 'MULTI_SCREEN' });
      }
    };
    checkScreens();
    const screenTarget = window.screen as Screen & EventTarget;
    const canListenScreen = typeof screenTarget.addEventListener === 'function';

    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('blur', onBlur);
    window.addEventListener('focus', onFocus);
    document.addEventListener('copy', onCopy);
    window.addEventListener('resize', onResize);
    if (canListenScreen) screenTarget.addEventListener('change', checkScreens);

    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('blur', onBlur);
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('copy', onCopy);
      window.removeEventListener('resize', onResize);
      if (canListenScreen) screenTarget.removeEventListener('change', checkScreens);
      window.clearTimeout(resizeTimer);
      window.clearTimeout(reminderTimer);
    };
  }, [enabled, report]);

  return { report, reminder, dismissReminder };
}
