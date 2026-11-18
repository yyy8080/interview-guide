import type { AnswerTelemetry } from '../types/recruitment';

/** 统计输入突增的滑动窗口 */
export const BURST_WINDOW_MS = 1000;

/**
 * 文字面试单题作答遥测采集器（纯逻辑，便于单元测试）。
 * <p>
 * 只统计数量与时间，不记录候选人输入的任何内容。
 */
export class AnswerTelemetryTracker {
  private readonly now: () => number;
  private startAt = 0;
  private firstInputAt: number | null = null;
  private typedChars = 0;
  private pastedChars = 0;
  private maxBurstChars = 0;
  private awayMs = 0;
  private pasteInFlight = false;
  private additions: { t: number; n: number }[] = [];

  constructor(now: () => number = Date.now) {
    this.now = now;
    this.reset();
  }

  /** 切换到新题目时重置 */
  reset(): void {
    this.startAt = this.now();
    this.firstInputAt = null;
    this.typedChars = 0;
    this.pastedChars = 0;
    this.maxBurstChars = 0;
    this.awayMs = 0;
    this.pasteInFlight = false;
    this.additions = [];
  }

  /** 粘贴事件：记录粘贴字数，紧随其后的一次内容变化归为粘贴而非键入 */
  recordPaste(chars: number): void {
    if (chars <= 0) return;
    this.markFirstInput();
    this.pastedChars += chars;
    this.pasteInFlight = true;
  }

  /** 内容变化：根据长度差统计键入字数与 1 秒窗口内的突增 */
  recordChange(prevLength: number, nextLength: number): void {
    const delta = nextLength - prevLength;
    if (delta !== 0) this.markFirstInput();
    if (this.pasteInFlight) {
      this.pasteInFlight = false;
      return;
    }
    if (delta <= 0) return;
    this.typedChars += delta;
    const t = this.now();
    this.additions.push({ t, n: delta });
    this.additions = this.additions.filter(a => t - a.t < BURST_WINDOW_MS);
    const burst = this.additions.reduce((sum, a) => sum + a.n, 0);
    this.maxBurstChars = Math.max(this.maxBurstChars, burst);
  }

  /** 本题作答期间离开页面 / 失焦的时长 */
  addAway(ms: number): void {
    if (ms > 0) this.awayMs += ms;
  }

  snapshot(): AnswerTelemetry {
    return {
      durationMs: Math.max(0, this.now() - this.startAt),
      firstInputMs: this.firstInputAt == null ? null : Math.max(0, this.firstInputAt - this.startAt),
      typedChars: this.typedChars,
      pastedChars: this.pastedChars,
      maxBurstChars: this.maxBurstChars,
      awayMs: this.awayMs,
    };
  }

  private markFirstInput(): void {
    if (this.firstInputAt == null) this.firstInputAt = this.now();
  }
}

/** 窗口面积相对开考时缩小到该比例以下视为疑似分屏 */
export const VIEWPORT_SHRINK_RATIO = 0.6;

export function isViewportShrunk(
  baseline: { width: number; height: number },
  current: { width: number; height: number },
): boolean {
  const baseArea = baseline.width * baseline.height;
  if (baseArea <= 0) return false;
  return (current.width * current.height) / baseArea < VIEWPORT_SHRINK_RATIO;
}
