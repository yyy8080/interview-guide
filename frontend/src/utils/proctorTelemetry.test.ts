import assert from 'node:assert/strict';
import test from 'node:test';
import { AnswerTelemetryTracker, isViewportShrunk } from './proctorTelemetry.ts';

function clock(start = 1_000) {
  let t = start;
  return {
    now: () => t,
    advance: (ms: number) => {
      t += ms;
    },
  };
}

test('键入与粘贴分开统计，粘贴后的内容变化不计入键入字数', () => {
  const c = clock();
  const tracker = new AnswerTelemetryTracker(c.now);
  c.advance(4_000);
  tracker.recordChange(0, 1);
  c.advance(300);
  tracker.recordChange(1, 3);
  tracker.recordPaste(200);
  tracker.recordChange(3, 203);
  c.advance(1_700);

  const snap = tracker.snapshot();
  assert.equal(snap.firstInputMs, 4_000);
  assert.equal(snap.typedChars, 3);
  assert.equal(snap.pastedChars, 200);
  assert.equal(snap.maxBurstChars, 3);
  assert.equal(snap.durationMs, 6_000);
});

test('非粘贴的 1 秒内大段新增被识别为输入突增，删除不计入', () => {
  const c = clock();
  const tracker = new AnswerTelemetryTracker(c.now);
  tracker.recordChange(0, 40);
  c.advance(500);
  tracker.recordChange(40, 90);
  c.advance(2_000);
  tracker.recordChange(90, 20);
  tracker.recordChange(20, 25);

  const snap = tracker.snapshot();
  assert.equal(snap.maxBurstChars, 90);
  assert.equal(snap.typedChars, 95);
});

test('切换题目后重置统计，离开时长按题累计', () => {
  const c = clock();
  const tracker = new AnswerTelemetryTracker(c.now);
  tracker.addAway(12_000);
  tracker.recordPaste(50);
  tracker.reset();
  tracker.addAway(3_000);
  tracker.addAway(2_000);

  const snap = tracker.snapshot();
  assert.equal(snap.awayMs, 5_000);
  assert.equal(snap.pastedChars, 0);
  assert.equal(snap.firstInputMs, null);
});

test('仅当窗口面积相对开考时缩小到 60% 以下才视为疑似分屏', () => {
  const base = { width: 1600, height: 900 };
  assert.equal(isViewportShrunk(base, { width: 1600, height: 820 }), false);
  assert.equal(isViewportShrunk(base, { width: 800, height: 900 }), true);
  assert.equal(isViewportShrunk({ width: 0, height: 0 }, { width: 10, height: 10 }), false);
});
