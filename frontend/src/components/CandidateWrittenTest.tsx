import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ClipboardEvent as ReactClipboardEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  CheckSquare,
  Clock,
  FileCheck2,
  Loader2,
  Send,
  ShieldCheck,
} from 'lucide-react';
import { candidateApi } from '../api/candidate';
import { getErrorMessage } from '../api/request';
import ConfirmDialog from './ConfirmDialog';
import { ROUTES } from '../constants/routes';
import {
  WRITTEN_QUESTION_TYPE_LABELS,
  type CandidateWrittenQuestion,
  type CandidateWrittenTest as CandidateWrittenTestState,
  type ProctorEventPayload,
  type WrittenTestStatus,
} from '../types/recruitment';

const LETTERS = ['A', 'B', 'C', 'D', 'E', 'F'];

function formatSeconds(sec: number): string {
  const mins = Math.floor(sec / 60);
  const secs = sec % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

function isAnswered(q: CandidateWrittenQuestion, val: string[] | undefined): boolean {
  if (!val || val.length === 0) return false;
  if (q.type === 'FILL_BLANK') {
    return Boolean(val[0]?.trim());
  }
  return true;
}

function normalizeAnswerMap(raw: Record<string, string[]> | undefined): Record<number, string[]> {
  const result: Record<number, string[]> = {};
  if (!raw) return result;
  for (const [k, v] of Object.entries(raw)) {
    const idx = Number(k);
    if (Number.isInteger(idx) && Array.isArray(v)) {
      result[idx] = v;
    }
  }
  return result;
}

interface CandidateWrittenTestProps {
  applicationId: number;
  jobTitle: string;
  plannedCount: number;
  timeLimitMinutes: number;
  initialStatus: WrittenTestStatus | null;
  onCompleted: () => void;
  onProctor: (event: ProctorEventPayload, immediate?: boolean) => void;
  onActiveChange: (active: boolean, focusIndex?: number) => void;
}

export default function CandidateWrittenTest({
  applicationId,
  jobTitle,
  plannedCount,
  timeLimitMinutes,
  initialStatus,
  onCompleted,
  onProctor,
  onActiveChange,
}: CandidateWrittenTestProps) {
  const [test, setTest] = useState<CandidateWrittenTestState | null>(null);
  const [answers, setAnswers] = useState<Record<number, string[]>>({});
  const [remaining, setRemaining] = useState<number | null>(null);
  const [loading, setLoading] = useState(initialStatus === 'IN_PROGRESS');
  const [starting, setStarting] = useState(false);
  const [savingHint, setSavingHint] = useState<'idle' | 'saving' | 'saved'>('idle');
  const [submitting, setSubmitting] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const answersRef = useRef(answers);
  answersRef.current = answers;
  const saveTimerRef = useRef<number | null>(null);
  const autoSubmittedRef = useRef(false);

  const applyTestState = useCallback(
    (data: CandidateWrittenTestState) => {
      if (data.status === 'SUBMITTED' || data.status === 'GRADED') {
        onActiveChange(false);
        onCompleted();
        return;
      }
      setTest(data);
      setAnswers(normalizeAnswerMap(data.answers));
      setRemaining(data.remainingSeconds);
      onActiveChange(data.status === 'IN_PROGRESS');
    },
    [onActiveChange, onCompleted],
  );

  useEffect(() => {
    if (initialStatus !== 'IN_PROGRESS') {
      setLoading(false);
      return;
    }
    let cancelled = false;
    setLoading(true);
    candidateApi
      .getWrittenTest(applicationId)
      .then(data => {
        if (!cancelled) applyTestState(data);
      })
      .catch(err => !cancelled && setError(getErrorMessage(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [applicationId, initialStatus, applyTestState]);

  useEffect(() => {
    return () => {
      if (saveTimerRef.current != null) {
        window.clearTimeout(saveTimerRef.current);
      }
    };
  }, []);

  const scheduleAutoSave = useCallback(
    (nextAnswers: Record<number, string[]>) => {
      if (saveTimerRef.current != null) {
        window.clearTimeout(saveTimerRef.current);
      }
      setSavingHint('saving');
      saveTimerRef.current = window.setTimeout(() => {
        candidateApi
          .saveWrittenAnswers(applicationId, nextAnswers)
          .then(() => setSavingHint('saved'))
          .catch(() => setSavingHint('idle'));
      }, 1200);
    },
    [applicationId],
  );

  const handleSubmit = useCallback(
    async (silent = false) => {
      if (submitting) return;
      if (saveTimerRef.current != null) {
        window.clearTimeout(saveTimerRef.current);
        saveTimerRef.current = null;
      }
      setSubmitting(true);
      if (!silent) setError(null);
      try {
        await candidateApi.submitWrittenTest(applicationId, answersRef.current);
        setConfirmOpen(false);
        onActiveChange(false);
        onCompleted();
      } catch (err) {
        setError(getErrorMessage(err));
      } finally {
        setSubmitting(false);
      }
    },
    [applicationId, onActiveChange, onCompleted, submitting],
  );

  // 倒计时，归零时自动交卷
  useEffect(() => {
    if (remaining == null || remaining <= 0 || test?.status !== 'IN_PROGRESS') {
      return;
    }
    const timer = window.setInterval(() => {
      setRemaining(prev => {
        if (prev == null) return null;
        if (prev <= 1) {
          if (!autoSubmittedRef.current) {
            autoSubmittedRef.current = true;
            void handleSubmit(true);
          }
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => window.clearInterval(timer);
  }, [remaining, test?.status, handleSubmit]);

  const handleStart = async () => {
    setStarting(true);
    setError(null);
    try {
      const started = await candidateApi.startWrittenTest(applicationId);
      applyTestState(started);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setStarting(false);
    }
  };

  const updateAnswer = (index: number, next: string[]) => {
    onActiveChange(true, index);
    setAnswers(prev => {
      const updated = { ...prev, [index]: next };
      scheduleAutoSave(updated);
      return updated;
    });
  };

  const toggleMultiOption = (index: number, letter: string) => {
    const current = answers[index] ?? [];
    const exists = current.includes(letter);
    const next = exists
      ? current.filter(item => item !== letter)
      : [...current, letter].sort();
    updateAnswer(index, next);
  };

  const answeredCount = useMemo(() => {
    if (!test) return 0;
    return test.questions.filter(q => isAnswered(q, answers[q.index])).length;
  }, [test, answers]);

  const unansweredCount = (test?.questions.length ?? 0) - answeredCount;

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  // 笔试未开始：说明与开始按钮
  if (!test || test.status !== 'IN_PROGRESS') {
    const count = test?.totalQuestions || plannedCount;
    return (
      <div className="mx-auto max-w-2xl space-y-6" data-testid="written-intro-card">
        <div className="dark-card space-y-5 p-6 sm:p-8">
          <div className="flex items-start gap-3">
            <div className="flex h-11 w-11 shrink-0 items-center justify-center rounded-2xl bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-400">
              <FileCheck2 className="h-6 w-6" />
            </div>
            <div>
              <span className="inline-block rounded-full bg-primary-50 px-2.5 py-0.5 text-xs font-medium text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
                环节 1 / 2 · 在线笔试
              </span>
              <h1 className="mt-1 text-xl font-bold text-slate-900 dark:text-white">
                {jobTitle} · 在线笔试
              </h1>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                预计 {count} 道客观与填空题 · 限时 {timeLimitMinutes} 分钟 · 交卷后进入 AI 面试
              </p>
            </div>
          </div>

          {error && (
            <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
              {error}
            </div>
          )}

          <div className="rounded-xl bg-slate-50 p-4 text-xs leading-relaxed text-slate-600 dark:bg-slate-800/60 dark:text-slate-300">
            <div className="mb-1.5 flex items-center gap-1.5 font-semibold text-slate-800 dark:text-slate-200">
              <ShieldCheck className="h-4 w-4 text-primary-500" />
              笔试作答须知
            </div>
            <ul className="list-disc space-y-1 pl-5">
              <li>包含单选题、多选题（全对得分，少选或错选不得分）与单空填空题，作答进度自动保存。</li>
              <li>点击「开始在线笔试」后由 AI 实时生成试卷并开始倒计时，到时自动交卷。</li>
              <li>无需开启摄像头或全屏；请本人独立作答，系统会无感记录离开页面、粘贴等行为供 HR 复核参考。</li>
            </ul>
          </div>

          <div className="flex justify-end gap-3">
            <Link to={ROUTES.careerMe} className="btn-secondary rounded-xl px-5 py-2.5 text-sm">
              稍后作答
            </Link>
            <button
              type="button"
              data-testid="start-written-btn"
              disabled={starting}
              onClick={handleStart}
              className="btn-primary flex items-center gap-2 rounded-xl px-6 py-2.5 text-sm font-medium disabled:opacity-60"
            >
              {starting && <Loader2 className="h-4 w-4 animate-spin" />}
              {starting ? '正在生成笔试题…' : '开始在线笔试'}
            </button>
          </div>
        </div>
      </div>
    );
  }

  // 笔试作答中
  return (
    <div className="mx-auto max-w-3xl space-y-4" data-testid="written-active-panel">
      <div className="dark-card sticky top-16 z-20 flex flex-wrap items-center justify-between gap-3 p-4">
        <div>
          <h1 className="text-base font-bold text-slate-900 dark:text-white">{jobTitle} · 在线笔试</h1>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            已答 {answeredCount} / {test.questions.length} 题
            {savingHint === 'saving' && ' · 正在自动保存…'}
            {savingHint === 'saved' && ' · 已自动保存'}
          </p>
        </div>

        <div className="flex items-center gap-3">
          {remaining != null && (
            <span
              className={`flex items-center gap-1 rounded-full px-3 py-1 text-xs font-semibold ${
                remaining < 180
                  ? 'bg-rose-50 text-rose-600 dark:bg-rose-900/30 dark:text-rose-300'
                  : 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200'
              }`}
            >
              <Clock className="h-3.5 w-3.5" />
              {formatSeconds(remaining)}
            </span>
          )}
          <button
            type="button"
            data-testid="submit-written-btn"
            disabled={submitting}
            onClick={() => {
              if (unansweredCount > 0) {
                setConfirmOpen(true);
              } else {
                void handleSubmit(false);
              }
            }}
            className="btn-primary flex items-center gap-1.5 rounded-xl px-4 py-2 text-xs font-medium disabled:opacity-60"
          >
            {submitting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Send className="h-3.5 w-3.5" />}
            提交笔试
          </button>
        </div>
      </div>

      {error && (
        <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
          {error}
        </div>
      )}

      <div className="space-y-4">
        {test.questions.map(q => {
          const current = answers[q.index] ?? [];
          return (
            <div
              key={q.index}
              data-testid={`written-question-${q.index}`}
              className="dark-card space-y-3 p-5"
            >
              <div
                onCopy={(e: ReactClipboardEvent<HTMLDivElement>) => {
                  e.preventDefault();
                  onProctor({ type: 'COPY', detail: '笔试', questionIndex: q.index }, true);
                }}
                onContextMenu={e => e.preventDefault()}
                onDragStart={e => e.preventDefault()}
                className="select-none space-y-1.5"
              >
                <div className="flex flex-wrap items-center gap-2 text-xs">
                  <span className="font-semibold text-primary-600 dark:text-primary-400">
                    第 {q.index + 1} 题
                  </span>
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-slate-700 dark:bg-slate-800 dark:text-slate-300">
                    {WRITTEN_QUESTION_TYPE_LABELS[q.type]}
                    {q.type === 'MULTIPLE_CHOICE' ? ' · 全对得分' : ''}
                  </span>
                  {q.category && (
                    <span className="text-slate-400">· {q.category}</span>
                  )}
                </div>
                <div className="whitespace-pre-wrap text-sm font-medium leading-relaxed text-slate-900 dark:text-slate-100">
                  {q.question}
                </div>
              </div>

              {q.type === 'FILL_BLANK' ? (
                <input
                  type="text"
                  data-testid={`written-fill-${q.index}`}
                  maxLength={200}
                  value={current[0] ?? ''}
                  onFocus={() => onActiveChange(true, q.index)}
                  onChange={e => updateAnswer(q.index, e.target.value ? [e.target.value] : [])}
                  onPaste={e => {
                    const chars = e.clipboardData?.getData('text')?.length ?? 0;
                    if (chars > 0) {
                      onProctor({ type: 'PASTE', detail: '笔试填空', questionIndex: q.index, chars });
                    }
                  }}
                  placeholder="请填写答案…"
                  className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                />
              ) : (
                <div className="grid gap-2">
                  {q.options.map((opt, idx) => {
                    const letter = LETTERS[idx] ?? String(idx + 1);
                    const selected = current.includes(letter);
                    return (
                      <button
                        key={letter}
                        type="button"
                        data-testid={`written-opt-${q.index}-${letter}`}
                        onClick={() => {
                          if (q.type === 'SINGLE_CHOICE') {
                            updateAnswer(q.index, [letter]);
                          } else {
                            toggleMultiOption(q.index, letter);
                          }
                        }}
                        className={`flex items-start gap-3 rounded-xl border px-3.5 py-2.5 text-left text-sm transition select-none ${
                          selected
                            ? 'border-primary-500 bg-primary-50/70 text-primary-900 dark:border-primary-400 dark:bg-primary-900/30 dark:text-primary-100'
                            : 'border-slate-200 text-slate-700 hover:border-slate-300 dark:border-slate-700 dark:text-slate-200'
                        }`}
                      >
                        <span
                          className={`mt-0.5 flex h-5 w-5 shrink-0 items-center justify-center rounded ${
                            q.type === 'MULTIPLE_CHOICE' ? 'rounded-md' : 'rounded-full'
                          } text-xs font-semibold ${
                            selected
                              ? 'bg-primary-500 text-white'
                              : 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'
                          }`}
                        >
                          {q.type === 'MULTIPLE_CHOICE' && selected ? (
                            <CheckSquare className="h-3.5 w-3.5" />
                          ) : (
                            letter
                          )}
                        </span>
                        <span className="leading-relaxed">{opt}</span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>
          );
        })}
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="确认提交笔试"
        message={`还有 ${unansweredCount} 道题目尚未作答，提交后不可修改并将进入 AI 面试环节。确定现在交卷吗？`}
        confirmText="确认交卷"
        confirmVariant="warning"
        loading={submitting}
        onConfirm={() => void handleSubmit(false)}
        onCancel={() => setConfirmOpen(false)}
      />
    </div>
  );
}
