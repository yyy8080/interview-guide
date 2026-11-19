import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { ClipboardEvent as ReactClipboardEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  AlertTriangle,
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
import CandidateLevelSelector from './CandidateLevelSelector';
import CandidatePreTestProfileForm from './CandidatePreTestProfileForm';
import AiWaitingOverlay from './waiting/AiWaitingOverlay';
import { QUESTION_GENERATION_TIPS } from './waiting/waitingTips';
import { ROUTES } from '../constants/routes';
import { JOB_DIFFICULTY_LABELS, type JobDifficulty } from '../types/job';
import {
  CANDIDATE_TRACK_LABELS,
  WRITTEN_QUESTION_TYPE_LABELS,
  type AiExperienceLevel,
  type CandidateTrack,
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
  candidateTrack?: CandidateTrack | null;
  candidateLevel: JobDifficulty;
  onLevelChange: (level: JobDifficulty) => void;
  aiExperience: AiExperienceLevel | null;
  onAiExperienceChange: (next: AiExperienceLevel | null) => void;
  specialties: string;
  onSpecialtiesChange: (next: string) => void;
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
  candidateTrack,
  candidateLevel,
  onLevelChange,
  aiExperience,
  onAiExperienceChange,
  specialties,
  onSpecialtiesChange,
  onCompleted,
  onProctor,
  onActiveChange,
}: CandidateWrittenTestProps) {
  const [test, setTest] = useState<CandidateWrittenTestState | null>(null);
  const [answers, setAnswers] = useState<Record<number, string[]>>({});
  const [remaining, setRemaining] = useState<number | null>(null);
  const [loading, setLoading] = useState(initialStatus === 'IN_PROGRESS');
  const [starting, setStarting] = useState(false);
  const [confirmStartOpen, setConfirmStartOpen] = useState(false);
  const [savingHint, setSavingHint] = useState<'idle' | 'saving' | 'saved'>('idle');
  const [submitting, setSubmitting] = useState(false);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const answersRef = useRef(answers);
  answersRef.current = answers;
  const saveTimerRef = useRef<number | null>(null);

  const applyTestState = useCallback(
    (data: CandidateWrittenTestState) => {
      if (data.candidateLevel) {
        onLevelChange(data.candidateLevel);
      }
      if (data.aiExperience !== undefined) {
        onAiExperienceChange(data.aiExperience ?? null);
      }
      if (typeof data.specialties === 'string') {
        onSpecialtiesChange(data.specialties);
      }
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
    [onActiveChange, onAiExperienceChange, onCompleted, onLevelChange, onSpecialtiesChange],
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

  const handleSubmit = useCallback(async () => {
    if (submitting) return;
    if (saveTimerRef.current != null) {
      window.clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    setSubmitting(true);
    setError(null);
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
  }, [applicationId, onActiveChange, onCompleted, submitting]);

  // 参考倒计时（归零后仅提示，绝不自动交卷；只能通过点击「提交笔试」按钮并确认后交卷）
  useEffect(() => {
    if (remaining == null || remaining <= 0 || test?.status !== 'IN_PROGRESS') {
      return;
    }
    const timer = window.setInterval(() => {
      setRemaining(prev => {
        if (prev == null) return null;
        return prev <= 1 ? 0 : prev - 1;
      });
    }, 1000);
    return () => window.clearInterval(timer);
  }, [remaining, test?.status]);

  const handleStart = async () => {
    setConfirmStartOpen(false);
    setStarting(true);
    setError(null);
    try {
      const started = await candidateApi.startWrittenTest(applicationId, candidateLevel, {
        aiExperience,
        specialties,
      });
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
  const effectiveTrack = test?.candidateTrack ?? candidateTrack ?? null;

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  // 笔试未开始：说明与开始按钮（技术方向沿用投递时所选，答题时无需再选）
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
              <div className="flex flex-wrap items-center gap-2">
                <span className="inline-block rounded-full bg-primary-50 px-2.5 py-0.5 text-xs font-medium text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
                  环节 1 / 2 · 在线笔试
                </span>
                {effectiveTrack && (
                  <span
                    data-testid="written-intro-track"
                    className="inline-block rounded-full bg-cyan-50 px-2.5 py-0.5 text-xs font-medium text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300"
                  >
                    出题方向：{CANDIDATE_TRACK_LABELS[effectiveTrack]}（投递时已选定）
                  </span>
                )}
              </div>
              <h1 className="mt-1 text-xl font-bold text-slate-900 dark:text-white">
                {jobTitle} · 在线笔试
              </h1>
              <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
                预计 {count} 道客观与填空题 · 参考时长 {timeLimitMinutes} 分钟 · 交卷后进入 AI 面试
              </p>
            </div>
          </div>

          {error && (
            <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
              {error}
            </div>
          )}

          <CandidateLevelSelector
            value={candidateLevel}
            onChange={onLevelChange}
            disabled={starting}
            label="选择您的应聘定位"
            hint="AI 将结合投递时所选前后端方向与当前定位实时生成笔试题目"
            testIdPrefix="written-level"
          />

          <CandidatePreTestProfileForm
            aiExperience={aiExperience}
            onAiExperienceChange={onAiExperienceChange}
            specialties={specialties}
            onSpecialtiesChange={onSpecialtiesChange}
            disabled={starting}
            testIdPrefix="written-profile"
          />

          <div
            data-testid="written-anti-cheat-warning"
            className="space-y-1.5 rounded-xl border border-amber-300/80 bg-amber-50/80 p-4 text-xs leading-relaxed text-amber-950 dark:border-amber-700/60 dark:bg-amber-900/25 dark:text-amber-100"
          >
            <div className="flex items-center gap-1.5 font-semibold text-amber-900 dark:text-amber-200">
              <AlertTriangle className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
              答题前重要提醒（严禁搜题与简历造假）
            </div>
            <ul className="list-disc space-y-1 pl-5">
              <li>
                <strong>答题时禁止搜题</strong>：严禁切换页面搜题、查阅资料、复制题目或借助外部 AI / 搜索工具代答。
              </li>
              <li>
                <strong>后台会有提交审查</strong>：系统后台会全程记录离开页面时长、粘贴文本与作答耗时并生成提交审查报告。
              </li>
              <li>
                <strong>诚信红线</strong>：请勿乱提交简历（提交简历仅有两次机会），<strong>简历不允许造假，否则会直接不通过</strong>。
              </li>
            </ul>
          </div>

          <div className="rounded-xl bg-slate-50 p-4 text-xs leading-relaxed text-slate-600 dark:bg-slate-800/60 dark:text-slate-300">
            <div className="mb-1.5 flex items-center gap-1.5 font-semibold text-slate-800 dark:text-slate-200">
              <ShieldCheck className="h-4 w-4 text-primary-500" />
              笔试作答须知
            </div>
            <ul className="list-disc space-y-1 pl-5">
              <li>包含单选题、多选题（全对得分，少选或错选不得分）与单空填空题，作答进度自动保存。</li>
              <li>点击「开始在线笔试」后由 AI 按您投递时所选技术方向与当前定位实时生成试卷；<strong>笔试仅在您点击「提交笔试」按钮并在确认框二次确认后才会交卷（系统不会自动交卷）</strong>。</li>
              <li>无需开启摄像头或全屏；请本人独立作答。</li>
            </ul>
          </div>

          <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
            <Link to={ROUTES.careerMe} className="btn-secondary rounded-xl px-5 py-2.5 text-center text-sm">
              稍后作答
            </Link>
            <button
              type="button"
              data-testid="start-written-btn"
              disabled={starting}
              onClick={() => setConfirmStartOpen(true)}
              className="btn-primary flex items-center justify-center gap-2 rounded-xl px-6 py-2.5 text-sm font-medium disabled:opacity-60"
            >
              {starting && <Loader2 className="h-4 w-4 animate-spin" />}
              {starting ? '正在生成笔试题…' : '开始在线笔试'}
            </button>
          </div>
        </div>

        <ConfirmDialog
          open={confirmStartOpen}
          title="开始在线笔试前确认"
          message={
            '请确认已知晓并遵守以下作答规范：\n1. 答题时禁止搜题、切屏查资料或粘贴外部答案，后台会有提交审查；\n2. 请勿乱提交简历（提交简历仅有两次机会），简历不允许造假，否则会直接不通过；\n3. 笔试作答完成后需手动点击「提交笔试」按钮并确认交卷。\n\n确认现在生成试卷并开始答题吗？'
          }
          confirmText="我已知晓，开始答题"
          confirmVariant="warning"
          confirmTestId="confirm-start-written-btn"
          loading={starting}
          onConfirm={() => void handleStart()}
          onCancel={() => setConfirmStartOpen(false)}
        />

        <AiWaitingOverlay
          open={starting}
          title="AI 正在为您生成专属笔试题"
          description="将结合岗位要求、投递时所选前后端方向与定位出题，题目生成后才开始计时，等待时间不计入作答时长。"
          estimate="通常需要 20–60 秒"
          tips={QUESTION_GENERATION_TIPS}
          testId="written-generating-overlay"
        />
      </div>
    );
  }

  // 笔试作答中
  const activeLevel = test.candidateLevel ?? candidateLevel;
  return (
    <div className="mx-auto max-w-3xl space-y-4" data-testid="written-active-panel">
      <div className="dark-card sticky top-[calc(3.5rem+env(safe-area-inset-top))] z-20 space-y-2.5 p-3 sm:top-[calc(4rem+env(safe-area-inset-top))] sm:p-4">
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-1.5 sm:gap-2">
              <h1 className="truncate text-sm font-bold text-slate-900 sm:text-base dark:text-white">{jobTitle} · 在线笔试</h1>
              {effectiveTrack && (
                <span
                  data-testid="written-active-track"
                  className="rounded-full bg-cyan-50 px-2 py-0.5 text-[11px] font-medium text-cyan-700 sm:px-2.5 sm:text-xs dark:bg-cyan-900/30 dark:text-cyan-300"
                >
                  方向：{CANDIDATE_TRACK_LABELS[effectiveTrack]}
                </span>
              )}
              <span
                data-testid="written-active-level"
                className="rounded-full bg-primary-50 px-2 py-0.5 text-[11px] font-medium text-primary-700 sm:px-2.5 sm:text-xs dark:bg-primary-900/30 dark:text-primary-300"
              >
                定位：{JOB_DIFFICULTY_LABELS[activeLevel]}
              </span>
              <span className="hidden rounded-full bg-amber-50 px-2.5 py-0.5 text-xs font-medium text-amber-800 sm:inline dark:bg-amber-900/30 dark:text-amber-300">
                禁止搜题 · 后台提交审查
              </span>
            </div>
            <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
              已答 {answeredCount} / {test.questions.length} 题
              {savingHint === 'saving' && ' · 正在自动保存…'}
              {savingHint === 'saved' && ' · 已自动保存'}
            </p>
          </div>

          <div className="flex shrink-0 items-center gap-2 sm:gap-3">
            {remaining != null && (
              <span
                data-testid="written-timer"
                className={`flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-semibold tabular-nums sm:px-3 ${
                  remaining < 180
                    ? 'bg-rose-50 text-rose-600 dark:bg-rose-900/30 dark:text-rose-300'
                    : 'bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-200'
                }`}
              >
                <Clock className="h-3.5 w-3.5" />
                {remaining === 0 ? '00:00 · 请点击提交' : formatSeconds(remaining)}
              </span>
            )}
            <button
              type="button"
              data-testid="submit-written-btn"
              disabled={submitting}
              onClick={() => setConfirmOpen(true)}
              className="btn-primary flex items-center gap-1.5 whitespace-nowrap rounded-xl px-3 py-2 text-xs font-medium disabled:opacity-60 sm:px-4"
            >
              {submitting ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Send className="h-3.5 w-3.5" />}
              提交笔试
            </button>
          </div>
        </div>
        <div className="h-1 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-700/60">
          <div
            className="h-full rounded-full bg-primary-500 transition-all"
            style={{ width: `${test.questions.length > 0 ? Math.round((answeredCount / test.questions.length) * 100) : 0}%` }}
          />
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
              className="dark-card space-y-3 p-4 sm:p-5"
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
                  onKeyDown={e => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                    }
                  }}
                  onChange={e => updateAnswer(q.index, e.target.value ? [e.target.value] : [])}
                  onPaste={e => {
                    const rawText = e.clipboardData?.getData('text') ?? '';
                    const chars = rawText.length;
                    if (chars > 0) {
                      onProctor({
                        type: 'PASTE',
                        detail: '笔试填空',
                        questionIndex: q.index,
                        chars,
                        pastedText: rawText.slice(0, 4000),
                      });
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
                        className={`flex min-h-[44px] items-start gap-3 rounded-xl border px-3.5 py-3 text-left text-sm transition select-none touch-manipulation sm:py-2.5 ${
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

      <div className="dark-card flex flex-col items-stretch justify-between gap-3 p-4 sm:flex-row sm:items-center sm:p-5">
        <div className="text-xs leading-relaxed text-slate-600 dark:text-slate-300">
          <span className="font-semibold text-slate-800 dark:text-slate-100">
            已作答 {answeredCount} / {test.questions.length} 题
          </span>
          {unansweredCount > 0 ? `（还有 ${unansweredCount} 题未作答）` : '（全部题目已作答）'}
          {' · '}系统不会自动交卷，请检查无误后点击右侧按钮提交笔试。
        </div>
        <button
          type="button"
          data-testid="submit-written-bottom-btn"
          disabled={submitting}
          onClick={() => setConfirmOpen(true)}
          className="btn-primary flex items-center justify-center gap-2 whitespace-nowrap rounded-xl px-5 py-2.5 text-sm font-medium disabled:opacity-60"
        >
          {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
          提交笔试
        </button>
      </div>

      <ConfirmDialog
        open={confirmOpen}
        title="确认提交笔试？"
        message={
          unansweredCount > 0
            ? `当前已作答 ${answeredCount} / ${test.questions.length} 题，还有 ${unansweredCount} 道题目尚未作答。\n\n笔试提交后将无法返回修改答案，并立即判分进入 AI 面试环节。为避免误提交，请确认是否现在提交笔试？`
            : `当前已完成全部 ${test.questions.length} 道题目作答。\n\n笔试提交后将无法返回修改答案，并立即判分进入 AI 面试环节。为避免误提交，请确认已检查无误并现在提交笔试？`
        }
        confirmText="确认提交"
        cancelText="返回检查"
        confirmVariant="warning"
        confirmTestId="confirm-submit-written-btn"
        cancelTestId="cancel-submit-written-btn"
        loading={submitting}
        onConfirm={() => void handleSubmit()}
        onCancel={() => setConfirmOpen(false)}
      />

      <AiWaitingOverlay
        open={submitting}
        delayMs={600}
        title="正在提交笔试并智能判分"
        description="填空题将由 AI 进行语义判分，完成后自动进入 AI 面试环节，请勿关闭页面。"
        estimate="通常需要 5–30 秒"
        testId="written-grading-overlay"
      />
    </div>
  );
}
