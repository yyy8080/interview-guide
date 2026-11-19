import { useCallback, useEffect, useRef, useState } from 'react';
import type { ClipboardEvent as ReactClipboardEvent } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import {
  AlertTriangle,
  CheckCircle2,
  Clock,
  Loader2,
  MessageSquare,
  Mic,
  RefreshCw,
  Send,
  ShieldCheck,
  X,
  XCircle,
} from 'lucide-react';
import { candidateApi } from '../../api/candidate';
import { getErrorMessage } from '../../api/request';
import CandidateAssessmentFeedbackCard from '../../components/CandidateAssessmentFeedbackCard';
import CandidateVoicePanel from '../../components/CandidateVoicePanel';
import CandidateWrittenTest from '../../components/CandidateWrittenTest';
import CandidateLevelSelector from '../../components/CandidateLevelSelector';
import CandidatePreTestProfileForm from '../../components/CandidatePreTestProfileForm';
import ConfirmDialog from '../../components/ConfirmDialog';
import ProctorWatermark from '../../components/ProctorWatermark';
import AiWaitingOverlay from '../../components/waiting/AiWaitingOverlay';
import AiWaitingPanel from '../../components/waiting/AiWaitingPanel';
import AiThinkingHint from '../../components/waiting/AiThinkingHint';
import { EVALUATION_TIPS, QUESTION_GENERATION_TIPS } from '../../components/waiting/waitingTips';
import { useProctorMonitor } from '../../hooks/useProctorMonitor';
import { AnswerTelemetryTracker } from '../../utils/proctorTelemetry';
import { ROUTES } from '../../constants/routes';
import { isCandidateAuthenticated } from '../../utils/candidateStorage';
import {
  JOB_DIFFICULTY_LABELS,
  type ConcreteInterviewMode,
  type JobDifficulty,
} from '../../types/job';
import {
  CANDIDATE_TRACK_LABELS,
  type AiExperienceLevel,
  type CandidateApplication,
  type CandidateInterviewState,
  type CandidateQuestion,
} from '../../types/recruitment';

interface QaItem {
  questionIndex: number;
  question: string;
  category: string | null;
  answer?: string;
}

function formatSeconds(sec: number): string {
  const mins = Math.floor(sec / 60);
  const secs = sec % 60;
  return `${String(mins).padStart(2, '0')}:${String(secs).padStart(2, '0')}`;
}

export default function CandidateInterviewPage() {
  const { applicationId } = useParams<{ applicationId: string }>();
  const authed = isCandidateAuthenticated();
  const id = Number(applicationId);

  const [app, setApp] = useState<CandidateApplication | null>(null);
  const [state, setState] = useState<CandidateInterviewState | null>(null);
  const [chosenMode, setChosenMode] = useState<ConcreteInterviewMode>('TEXT');
  const [chosenLevel, setChosenLevel] = useState<JobDifficulty>('mid');
  const [aiExperience, setAiExperience] = useState<AiExperienceLevel | null>(null);
  const [specialties, setSpecialties] = useState('');
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [confirmStartOpen, setConfirmStartOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [completing, setCompleting] = useState(false);
  const [confirmCompleteOpen, setConfirmCompleteOpen] = useState(false);
  const [writtenActive, setWrittenActive] = useState(false);
  const [writtenJustFinished, setWrittenJustFinished] = useState(false);
  const [refreshingResult, setRefreshingResult] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [answer, setAnswer] = useState('');
  const [history, setHistory] = useState<QaItem[]>([]);
  const [remaining, setRemaining] = useState<number | null>(null);

  // 当前题作答遥测
  const [telemetry] = useState(() => new AnswerTelemetryTracker());
  const currentQuestionIndexRef = useRef<number | undefined>(undefined);
  const writtenFocusIndexRef = useRef<number | undefined>(undefined);
  const chatScrollRef = useRef<HTMLDivElement | null>(null);

  // 文字面试：新题目 / AI 思考状态变化时，聊天区自动滚动到底部（手机端尤为重要）
  useEffect(() => {
    const el = chatScrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [history.length, submitting]);

  const applyQuestion = (q: CandidateQuestion | null) => {
    if (!q) return;
    setHistory(prev => {
      if (prev.some(item => item.questionIndex === q.questionIndex)) {
        return prev;
      }
      return [
        ...prev,
        {
          questionIndex: q.questionIndex,
          question: q.question,
          category: q.category,
        },
      ];
    });
  };

  useEffect(() => {
    if (!authed || !applicationId || Number.isNaN(id)) {
      setLoading(false);
      return;
    }
    Promise.all([candidateApi.getApplication(id), candidateApi.getInterview(id).catch(() => null)])
      .then(([appData, interviewData]) => {
        setApp(appData);
        if (appData.availableModes.length > 0) {
          setChosenMode(appData.availableModes[0]);
        }
        const initialLevel = interviewData?.candidateLevel ?? appData.candidateLevel;
        if (initialLevel) {
          setChosenLevel(initialLevel);
        }
        setAiExperience(interviewData?.aiExperience ?? appData.aiExperience ?? null);
        setSpecialties(interviewData?.specialties ?? appData.specialties ?? '');
        if (interviewData) {
          setState(interviewData);
          setRemaining(interviewData.remainingSeconds);
          applyQuestion(interviewData.currentQuestion);
        }
      })
      .catch(err => setError(getErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [authed, applicationId, id]);

  // 倒计时
  useEffect(() => {
    if (remaining == null || remaining <= 0 || !state || state.finished || state.stage !== 'INTERVIEWING') {
      return;
    }
    const timer = window.setInterval(() => {
      setRemaining(prev => {
        if (prev == null) return null;
        if (prev <= 1) {
          candidateApi.getInterview(id).then(setState).catch(() => {});
          return 0;
        }
        return prev - 1;
      });
    }, 1000);
    return () => window.clearInterval(timer);
  }, [remaining, state, id]);

  // 作答行为无感采集（笔试进行中与面试进行中生效，不弹窗不阻断）
  const isInterviewing = state?.stage === 'INTERVIEWING' && !state.finished;
  const isCompletedView = Boolean(
    app
      && (state?.finished
        || (state && state.stage !== 'INTERVIEW_PENDING' && state.stage !== 'INTERVIEWING')
        || (app.stage !== 'INTERVIEW_PENDING' && app.stage !== 'INTERVIEWING' && !isInterviewing)),
  );

  const refreshApplication = useCallback(async () => {
    if (!authed || !applicationId || Number.isNaN(id)) return;
    setRefreshingResult(true);
    try {
      const latest = await candidateApi.getApplication(id);
      setApp(latest);
    } catch {
      // ignore transient error
    } finally {
      setRefreshingResult(false);
    }
  }, [authed, applicationId, id]);

  // 交卷进入完成页后，自动同步投递评估状态（若 AI 异步评分未通过可立即呈现原因与错题解析）
  useEffect(() => {
    if (!isCompletedView || !app) return;
    if (
      app.stage !== 'INTERVIEW_PENDING'
      && app.stage !== 'INTERVIEWING'
      && app.stage !== 'UNDER_REVIEW'
    ) {
      return;
    }
    let cancelled = false;
    let attempts = 0;
    const poll = async () => {
      attempts += 1;
      try {
        const latest = await candidateApi.getApplication(id);
        if (!cancelled) {
          setApp(latest);
        }
      } catch {
        // ignore
      }
    };
    void poll();
    const timer = window.setInterval(() => {
      if (attempts >= 45) {
        window.clearInterval(timer);
        return;
      }
      void poll();
    }, 4000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [isCompletedView, app?.stage, id]);

  const textQuestionIndex = state?.mode === 'TEXT' ? state.currentQuestion?.questionIndex : undefined;
  useEffect(() => {
    currentQuestionIndexRef.current = textQuestionIndex;
    telemetry.reset();
  }, [textQuestionIndex, telemetry]);

  const handleWrittenActiveChange = useCallback((active: boolean, focusIndex?: number) => {
    setWrittenActive(active);
    if (focusIndex !== undefined) {
      writtenFocusIndexRef.current = focusIndex;
    }
  }, []);

  const handleWrittenCompleted = useCallback(() => {
    setWrittenActive(false);
    setWrittenJustFinished(true);
    candidateApi
      .getInterview(id)
      .then(data => {
        setState(data);
        if (data.candidateLevel) {
          setChosenLevel(data.candidateLevel);
        }
        if (data.aiExperience !== undefined) {
          setAiExperience(data.aiExperience ?? null);
        }
        if (typeof data.specialties === 'string') {
          setSpecialties(data.specialties);
        }
      })
      .catch(() => {
        setState(prev => (prev ? { ...prev, writtenTestStatus: 'GRADED' } : prev));
      });
  }, [id]);

  const getQuestionIndex = useCallback(
    () => (isInterviewing ? currentQuestionIndexRef.current : writtenFocusIndexRef.current),
    [isInterviewing],
  );
  const handleAway = useCallback(
    (ms: number) => {
      if (isInterviewing) telemetry.addAway(ms);
    },
    [isInterviewing, telemetry],
  );
  const { report: reportProctor, reminder, dismissReminder } = useProctorMonitor({
    applicationId: id,
    enabled: isInterviewing || writtenActive,
    awayReminder: state?.proctorAwayReminder ?? true,
    getQuestionIndex,
    onAway: handleAway,
  });

  const handleAnswerPaste = (e: ReactClipboardEvent<HTMLTextAreaElement>) => {
    const rawText = e.clipboardData?.getData('text') ?? '';
    const chars = rawText.length;
    telemetry.recordPaste(chars);
    if (chars > 0) {
      reportProctor({
        type: 'PASTE',
        detail: '文字面试作答',
        chars,
        pastedText: rawText.slice(0, 4000),
      });
    }
  };

  /** 题干区域：不可选中复制、禁用右键菜单（作答区不受影响） */
  const protectQuestion = {
    onCopy: (e: ReactClipboardEvent<HTMLElement>) => {
      e.preventDefault();
      reportProctor({ type: 'COPY' }, true);
    },
    onContextMenu: (e: { preventDefault: () => void }) => e.preventDefault(),
    onDragStart: (e: { preventDefault: () => void }) => e.preventDefault(),
  };

  if (!authed) {
    const redirect = applicationId ? ROUTES.careerInterview(id) : ROUTES.careerMe;
    return <Navigate to={`${ROUTES.careerLogin}?redirect=${encodeURIComponent(redirect)}`} replace />;
  }

  const handleStart = async () => {
    setConfirmStartOpen(false);
    setStarting(true);
    setError(null);
    try {
      const started = await candidateApi.startInterview(id, chosenMode, chosenLevel, {
        aiExperience,
        specialties,
      });
      setState(started);
      if (started.candidateLevel) {
        setChosenLevel(started.candidateLevel);
      }
      if (started.aiExperience !== undefined) {
        setAiExperience(started.aiExperience ?? null);
      }
      if (typeof started.specialties === 'string') {
        setSpecialties(started.specialties);
      }
      setRemaining(started.remainingSeconds);
      applyQuestion(started.currentQuestion);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setStarting(false);
    }
  };

  const handleSubmitAnswer = async () => {
    if (!state?.currentQuestion || !answer.trim()) return;
    const currentQ = state.currentQuestion;
    const submittedText = answer.trim();
    setSubmitting(true);
    setError(null);
    try {
      const res = await candidateApi.submitAnswer(
        id,
        currentQ.questionIndex,
        submittedText,
        telemetry.snapshot(),
      );
      setHistory(prev =>
        prev.map(item =>
          item.questionIndex === currentQ.questionIndex ? { ...item, answer: submittedText } : item,
        ),
      );
      setAnswer('');
      setState(prev =>
        prev
          ? {
              ...prev,
              finished: res.finished,
              stage: res.finished ? 'UNDER_REVIEW' : prev.stage,
              currentQuestionIndex: res.currentQuestionIndex,
              totalQuestions: res.totalQuestions,
              currentQuestion: res.nextQuestion,
            }
          : prev,
      );
      applyQuestion(res.nextQuestion);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  const handleCompleteEarly = async () => {
    setCompleting(true);
    setError(null);
    try {
      const ended = await candidateApi.completeInterview(id);
      setState(ended);
      setConfirmCompleteOpen(false);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setCompleting(false);
    }
  };

  const handleEndVoice = async () => {
    setCompleting(true);
    setError(null);
    try {
      const ended = await candidateApi.endVoiceInterview(id);
      setState(ended);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setCompleting(false);
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  if (!app) {
    return (
      <div className="dark-card py-16 text-center">
        <p className="text-slate-600 dark:text-slate-300">{error || '投递记录不存在'}</p>
        <Link to={ROUTES.careerMe} className="btn-primary mt-4 inline-block rounded-xl px-5 py-2 text-sm">
          返回我的投递
        </Link>
      </div>
    );
  }

  // 已完成视图（若评估未通过，直接展示未通过原因与没通过的题目解析）
  if (isCompletedView) {
    if (app.stage === 'REJECTED') {
      return (
        <div className="mx-auto max-w-3xl space-y-5" data-testid="interview-rejected-result">
          <div className="dark-card space-y-5 p-4 text-center sm:p-8">
            <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-rose-50 text-rose-600 dark:bg-rose-900/30 dark:text-rose-400">
              <XCircle className="h-8 w-8" />
            </div>
            <div>
              <h1 className="text-lg font-bold text-slate-900 sm:text-xl dark:text-white">
                {app.jobTitle} · 本次评估未通过
              </h1>
              <p className="mt-1.5 text-sm text-slate-600 dark:text-slate-300">
                以下为您整理了本次评估未通过的具体原因及未达标题目的参考解析，供后续复盘提升参考。
              </p>
            </div>

            {app.assessmentFeedback ? (
              <div className="text-left">
                <CandidateAssessmentFeedbackCard
                  applicationId={app.id}
                  feedback={app.assessmentFeedback}
                />
              </div>
            ) : (
              <p className="text-sm text-slate-500 dark:text-slate-400">
                感谢您的关注与投入，经综合评估本次未能进入下一环节，祝您求职顺利。
              </p>
            )}

            <div className="pt-1">
              <Link to={ROUTES.careerMe} className="btn-primary block rounded-xl px-6 py-2.5 text-sm sm:inline-block">
                返回我的投递
              </Link>
            </div>
          </div>
        </div>
      );
    }

    const evaluating =
      app.stage === 'UNDER_REVIEW' || app.stage === 'INTERVIEWING' || app.stage === 'INTERVIEW_PENDING';

    return (
      <div className="mx-auto max-w-xl space-y-4">
        <div className="dark-card space-y-4 p-5 text-center sm:p-8">
          <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-400">
            <CheckCircle2 className="h-8 w-8" />
          </div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-white">在线面试已完成</h1>
          <p className="text-sm text-slate-600 dark:text-slate-300">
            感谢您参加「{app.jobTitle}」岗位的在线面试。系统正在整理评估报告并提交 HR 复核，您也可稍后在「我的投递」查看评估进度或题目解析。
          </p>

          {evaluating && (
            <div className="text-left">
              <AiWaitingPanel
                variant="card"
                title="AI 正在生成您的面试评估报告"
                description="您可以留在本页玩个小游戏，也可以离开页面，稍后在「我的投递」查看结果。"
                estimate="通常需要 1–3 分钟"
                tips={EVALUATION_TIPS}
                testId="interview-evaluating-panel"
              />
            </div>
          )}

          <div className="flex flex-col-reverse items-stretch justify-center gap-3 pt-1 sm:flex-row sm:items-center">
            <button
              type="button"
              data-testid="refresh-interview-result-btn"
              disabled={refreshingResult}
              onClick={() => void refreshApplication()}
              className="btn-secondary inline-flex items-center justify-center gap-1.5 rounded-xl px-4 py-2.5 text-sm disabled:opacity-60"
            >
              <RefreshCw className={`h-4 w-4 ${refreshingResult ? 'animate-spin' : ''}`} />
              刷新评估状态
            </button>
            <Link to={ROUTES.careerMe} className="btn-primary inline-block rounded-xl px-6 py-2.5 text-center text-sm">
              返回我的投递
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const reminderToast = reminder ? (
    <div
      role="status"
      className="fixed left-3 right-3 top-[calc(4rem+env(safe-area-inset-top))] z-50 flex items-center justify-between gap-2 rounded-xl bg-white/95 px-4 py-2.5 text-sm text-slate-700 shadow-lg ring-1 ring-slate-200 sm:left-auto sm:right-4 sm:top-20 sm:justify-start dark:bg-slate-800/95 dark:text-slate-200 dark:ring-slate-700"
    >
      <span>{reminder}</span>
      <button
        type="button"
        aria-label="关闭提醒"
        onClick={dismissReminder}
        className="rounded p-0.5 text-slate-400 hover:text-slate-600"
      >
        <X className="h-3.5 w-3.5" />
      </button>
    </div>
  ) : null;

  const needsWrittenTest = Boolean(
    !isInterviewing
      && state?.writtenTestEnabled
      && state.writtenTestStatus !== 'SUBMITTED'
      && state.writtenTestStatus !== 'GRADED',
  );
  const effectiveTrack = state?.candidateTrack ?? app.candidateTrack ?? null;

  // 环节 1：在线笔试（在 AI 面试前完成）
  if (needsWrittenTest && state) {
    return (
      <>
        <CandidateWrittenTest
          applicationId={id}
          jobTitle={app.jobTitle}
          plannedCount={state.writtenQuestionCount ?? 10}
          timeLimitMinutes={state.writtenTimeLimitMinutes ?? 15}
          initialStatus={state.writtenTestStatus ?? null}
          candidateTrack={effectiveTrack}
          candidateLevel={chosenLevel}
          onLevelChange={setChosenLevel}
          aiExperience={aiExperience}
          onAiExperienceChange={setAiExperience}
          specialties={specialties}
          onSpecialtiesChange={setSpecialties}
          onCompleted={handleWrittenCompleted}
          onProctor={reportProctor}
          onActiveChange={handleWrittenActiveChange}
        />
        {reminderToast}
        {writtenActive && state.watermarkText && <ProctorWatermark text={state.watermarkText} />}
      </>
    );
  }

  // 尚未开始面试：选择定位 + 补充特长/AI经验 + 面试形式 + 须知（技术方向沿用投递时所选，无需再选）
  if (!isInterviewing) {
    return (
      <div className="mx-auto max-w-2xl space-y-6">
        <div className="dark-card space-y-5 p-4 sm:p-8">
          <div>
            <div className="mb-1 flex flex-wrap items-center gap-2">
              {state?.writtenTestEnabled && (
                <span className="inline-block rounded-full bg-emerald-50 px-2.5 py-0.5 text-xs font-medium text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300">
                  环节 2 / 2 · 在线笔试已完成，请继续完成 AI 面试
                </span>
              )}
              {effectiveTrack && (
                <span
                  data-testid="interview-intro-track"
                  className="inline-block rounded-full bg-cyan-50 px-2.5 py-0.5 text-xs font-medium text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300"
                >
                  出题方向：{CANDIDATE_TRACK_LABELS[effectiveTrack]}（投递时已选定）
                </span>
              )}
            </div>
            <h1 className="text-xl font-bold text-slate-900 dark:text-white">
              {app.jobTitle} · 在线 AI 面试
            </h1>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
              {app.questionCount ? `预计约 ${app.questionCount} 道题目` : '智能自适应提问'}
              {app.timeLimitMinutes ? ` · 限时 ${app.timeLimitMinutes} 分钟` : ''}
            </p>
          </div>

          {writtenJustFinished && (
            <div
              data-testid="written-finished-banner"
              className="rounded-xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-700 dark:bg-emerald-900/25 dark:text-emerald-300"
            >
              在线笔试已交卷，系统已在后台判分。现在请选择形式进入 AI 面试。
            </div>
          )}

          {error && (
            <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
              {error}
            </div>
          )}

          <CandidateLevelSelector
            value={chosenLevel}
            onChange={setChosenLevel}
            disabled={starting}
            label="选择您的应聘定位"
            hint="AI 将结合投递时所选前后端方向与当前定位生成面试题目"
            testIdPrefix="interview-level"
          />

          <CandidatePreTestProfileForm
            aiExperience={aiExperience}
            onAiExperienceChange={setAiExperience}
            specialties={specialties}
            onSpecialtiesChange={setSpecialties}
            disabled={starting}
            testIdPrefix="interview-profile"
          />

          {/* 面试形式选择 */}
          <div className="space-y-2">
            <label className="block text-sm font-semibold text-slate-800 dark:text-slate-200">
              {app.availableModes.length > 1 ? '请选择您的面试形式' : '本岗位面试形式'}
            </label>
            <div className="grid gap-3 sm:grid-cols-2">
              {app.availableModes.includes('TEXT') && (
                <button
                  type="button"
                  onClick={() => setChosenMode('TEXT')}
                  className={`flex items-start gap-3 rounded-2xl border p-4 text-left transition ${
                    chosenMode === 'TEXT'
                      ? 'border-primary-500 bg-primary-50/60 dark:border-primary-400 dark:bg-primary-900/25'
                      : 'border-slate-200 hover:border-slate-300 dark:border-slate-700'
                  }`}
                >
                  <MessageSquare className="mt-0.5 h-5 w-5 shrink-0 text-primary-500" />
                  <div>
                    <div className="font-semibold text-slate-900 dark:text-white">文字问答面试</div>
                    <div className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                      逐题阅读并键盘输入作答，适合安静打字环境
                    </div>
                  </div>
                </button>
              )}

              {app.availableModes.includes('VOICE') && (
                <button
                  type="button"
                  onClick={() => setChosenMode('VOICE')}
                  className={`flex items-start gap-3 rounded-2xl border p-4 text-left transition ${
                    chosenMode === 'VOICE'
                      ? 'border-primary-500 bg-primary-50/60 dark:border-primary-400 dark:bg-primary-900/25'
                      : 'border-slate-200 hover:border-slate-300 dark:border-slate-700'
                  }`}
                >
                  <Mic className="mt-0.5 h-5 w-5 shrink-0 text-primary-500" />
                  <div>
                    <div className="font-semibold text-slate-900 dark:text-white">实时语音面试</div>
                    <div className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                      AI 面试官语音互动提问，结合您简历中的项目与经历细节核验真实性
                    </div>
                  </div>
                </button>
              )}
            </div>
          </div>

          {/* 答题前醒目警示 */}
          <div
            data-testid="interview-anti-cheat-warning"
            className="space-y-1.5 rounded-xl border border-amber-300/80 bg-amber-50/80 p-4 text-xs leading-relaxed text-amber-950 dark:border-amber-700/60 dark:bg-amber-900/25 dark:text-amber-100"
          >
            <div className="flex items-center gap-1.5 font-semibold text-amber-900 dark:text-amber-200">
              <AlertTriangle className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
              答题前重要提醒（严禁搜题与简历造假）
            </div>
            <ul className="list-disc space-y-1 pl-5">
              <li>
                <strong>答题时禁止搜题</strong>：面试作答期间严禁搜题、切换页面查资料、粘贴外部答案或借助工具代答，<strong>后台会有提交审查</strong>。
              </li>
              <li>
                <strong>简历不允许造假，否则会直接不通过</strong>：请勿乱提交简历（提交简历仅有两次机会），AI 面试官将围绕您简历中的经历逐项核验真实性。
              </li>
            </ul>
          </div>

          {/* 面试须知 */}
          <div className="rounded-xl bg-slate-50 p-4 text-xs leading-relaxed text-slate-600 dark:bg-slate-800/60 dark:text-slate-300">
            <div className="mb-1.5 flex items-center gap-1.5 font-semibold text-slate-800 dark:text-slate-200">
              <ShieldCheck className="h-4 w-4 text-primary-500" />
              诚信面试须知
            </div>
            <ul className="list-disc space-y-1 pl-5">
              <li>点击「开始在线面试」后由 AI 按您投递时所选技术方向与当前定位生成面试题目，题目生成完毕后开始计时（等待生成的时间不计入作答时长），请确保网络通畅、环境安静。</li>
              <li>语音面试将重点围绕您简历中填写的项目、模块与成果细节提问以核验简历真实性，请结合本人真实经历作答。</li>
              <li>本次面试不会开启摄像头，也无需全屏；系统后台会无感记录离开页面、粘贴等作答行为并纳入提交审查。</li>
            </ul>
          </div>

          <div className="flex flex-col-reverse gap-3 sm:flex-row sm:justify-end">
            <Link to={ROUTES.careerMe} className="btn-secondary rounded-xl px-5 py-2.5 text-center text-sm">
              稍后再面
            </Link>
            <button
              type="button"
              data-testid="start-interview-btn"
              disabled={starting}
              onClick={() => setConfirmStartOpen(true)}
              className="btn-primary flex items-center justify-center gap-2 rounded-xl px-6 py-2.5 text-sm font-medium disabled:opacity-60"
            >
              {starting && <Loader2 className="h-4 w-4 animate-spin" />}
              {starting ? '正在生成面试题目…' : '开始在线面试'}
            </button>
          </div>
        </div>

        <ConfirmDialog
          open={confirmStartOpen}
          title="开始在线面试前确认"
          message={
            '请确认已知晓并遵守以下作答规范：\n1. 答题时禁止搜题、切屏查资料或粘贴外部答案，后台会有提交审查；\n2. 请勿乱提交简历（提交简历仅有两次机会），简历不允许造假，否则会直接不通过。\n\n确认现在生成面试题目并开始作答吗？'
          }
          confirmText="我已知晓，开始面试"
          confirmVariant="warning"
          confirmTestId="confirm-start-interview-btn"
          loading={starting}
          onConfirm={() => void handleStart()}
          onCancel={() => setConfirmStartOpen(false)}
        />

        <AiWaitingOverlay
          open={starting}
          title={chosenMode === 'VOICE' ? 'AI 语音面试官正在准备开场' : 'AI 正在为您生成面试题目'}
          description={
            chosenMode === 'VOICE'
              ? '正在结合您的简历、投递时所选技术方向与定位准备追问方向，准备就绪后将自动进入语音面试，计时从进入后开始。'
              : '正在结合岗位要求、您的简历、投递时所选技术方向与定位生成题目，题目生成后才开始计时，等待时间不计入作答时长。'
          }
          estimate="通常需要 15–60 秒"
          tips={QUESTION_GENERATION_TIPS}
          testId="interview-generating-overlay"
        />
      </div>
    );
  }

  const activeLevel = state.candidateLevel ?? chosenLevel;

  // 进行中：语音模式
  if (state.mode === 'VOICE' && state.voiceSessionId) {
    return (
      <div className="space-y-3 sm:space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex min-w-0 flex-wrap items-center gap-1.5 sm:gap-2">
            <h1 className="text-base font-bold text-slate-900 sm:text-lg dark:text-white">
              {state.jobTitle} · 语音面试进行中
            </h1>
            {effectiveTrack && (
              <span
                data-testid="interview-active-track"
                className="rounded-full bg-cyan-50 px-2.5 py-0.5 text-xs font-medium text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300"
              >
                方向：{CANDIDATE_TRACK_LABELS[effectiveTrack]}
              </span>
            )}
            <span
              data-testid="interview-active-level"
              className="rounded-full bg-primary-50 px-2.5 py-0.5 text-xs font-medium text-primary-700 dark:bg-primary-900/30 dark:text-primary-300"
            >
              定位：{JOB_DIFFICULTY_LABELS[activeLevel]}
            </span>
            <span className="hidden rounded-full bg-slate-100 px-2.5 py-0.5 text-xs text-slate-600 sm:inline dark:bg-slate-800 dark:text-slate-300">
              含简历真实性追问
            </span>
            <span className="hidden rounded-full bg-amber-50 px-2.5 py-0.5 text-xs font-medium text-amber-800 sm:inline dark:bg-amber-900/30 dark:text-amber-300">
              禁止搜题 · 后台提交审查
            </span>
          </div>
          {remaining != null && (
            <span className="flex items-center gap-1.5 rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold tabular-nums text-slate-700 dark:bg-slate-800 dark:text-slate-200">
              <Clock className="h-3.5 w-3.5 text-primary-500" />
              剩余 {formatSeconds(remaining)}
            </span>
          )}
        </div>

        <CandidateVoicePanel
          voiceSessionId={state.voiceSessionId}
          onEndInterview={handleEndVoice}
          ending={completing}
          onProctor={reportProctor}
        />
        {reminderToast}
        {state.watermarkText && <ProctorWatermark text={state.watermarkText} />}
        <AiWaitingOverlay
          open={completing}
          delayMs={600}
          title="正在结束语音面试并提交"
          description="正在保存对话记录并提交评估，完成后将自动跳转结果页。"
          allowGame={false}
          testId="voice-ending-overlay"
        />
      </div>
    );
  }

  // 进行中：文字问答模式
  const currentQ = state.currentQuestion;
  const progress =
    state.totalQuestions > 0 && currentQ
      ? Math.round(((currentQ.questionIndex + 1) / state.totalQuestions) * 100)
      : 0;

  return (
    <div className="mx-auto max-w-3xl space-y-3 sm:space-y-4">
      <div className="dark-card space-y-2.5 p-3 sm:p-4">
        <div className="flex items-center justify-between gap-3">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-1.5 sm:gap-2">
              <h1 className="text-sm font-bold text-slate-900 sm:text-base dark:text-white">{state.jobTitle} · 在线文字面试</h1>
              {effectiveTrack && (
                <span
                  data-testid="interview-active-track"
                  className="rounded-full bg-cyan-50 px-2 py-0.5 text-[11px] font-medium text-cyan-700 sm:px-2.5 sm:text-xs dark:bg-cyan-900/30 dark:text-cyan-300"
                >
                  方向：{CANDIDATE_TRACK_LABELS[effectiveTrack]}
                </span>
              )}
              <span
                data-testid="interview-active-level"
                className="rounded-full bg-primary-50 px-2 py-0.5 text-[11px] font-medium text-primary-700 sm:px-2.5 sm:text-xs dark:bg-primary-900/30 dark:text-primary-300"
              >
                定位：{JOB_DIFFICULTY_LABELS[activeLevel]}
              </span>
              <span className="hidden rounded-full bg-amber-50 px-2.5 py-0.5 text-xs font-medium text-amber-800 sm:inline dark:bg-amber-900/30 dark:text-amber-300">
                禁止搜题 · 后台提交审查
              </span>
            </div>
            <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
              第 {currentQ ? currentQ.questionIndex + 1 : state.currentQuestionIndex} / {state.totalQuestions} 题（{progress}%）
            </p>
          </div>

          <div className="flex shrink-0 items-center gap-2 sm:gap-3">
            {remaining != null && (
              <span
                className={`flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-semibold tabular-nums sm:px-3 ${
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
              onClick={() => setConfirmCompleteOpen(true)}
              className="whitespace-nowrap rounded-lg px-1.5 py-1 text-xs font-medium text-slate-500 hover:text-rose-600 dark:text-slate-400"
            >
              提前交卷
            </button>
          </div>
        </div>
        <div className="h-1 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-700/60">
          <div className="h-full rounded-full bg-primary-500 transition-all" style={{ width: `${progress}%` }} />
        </div>
      </div>

      {error && (
        <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
          {error}
        </div>
      )}

      {/* 问答对话流 */}
      <div
        ref={chatScrollRef}
        className="dark-card max-h-[45dvh] space-y-4 overflow-y-auto overscroll-contain p-4 sm:max-h-[420px] sm:p-6"
      >
        {history.map(item => (
          <div key={item.questionIndex} className="space-y-3">
            <div
              {...protectQuestion}
              data-testid="question-stem"
              className="select-none rounded-2xl bg-slate-50 p-3.5 text-sm text-slate-800 sm:p-4 dark:bg-slate-800/70 dark:text-slate-100"
            >
              <div className="mb-1 flex items-center gap-2 text-xs text-primary-600 dark:text-primary-400">
                <span className="font-semibold">问题 {item.questionIndex + 1}</span>
                {item.category && <span>· {item.category}</span>}
              </div>
              <div className="whitespace-pre-wrap leading-relaxed">{item.question}</div>
            </div>

            {item.answer && (
              <div className="ml-6 whitespace-pre-wrap rounded-2xl bg-primary-500 px-4 py-3 text-sm text-white sm:ml-8">
                {item.answer}
              </div>
            )}
          </div>
        ))}
      </div>

      {/* 作答框（移动端吸底，便于软键盘弹出时继续作答） */}
      {currentQ && (
        <div className="dark-card sticky bottom-[calc(0.5rem+env(safe-area-inset-bottom))] z-10 space-y-3 p-3 sm:static sm:p-4">
          <AiThinkingHint active={submitting} testId="text-interview-thinking" />
          <textarea
            rows={4}
            value={answer}
            disabled={submitting}
            onChange={e => {
              telemetry.recordChange(answer.length, e.target.value.length);
              setAnswer(e.target.value);
            }}
            onPaste={handleAnswerPaste}
            onKeyDown={e => {
              if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                handleSubmitAnswer();
              }
            }}
            placeholder="请输入您的回答…（支持 Ctrl / Cmd + Enter 提交本题）"
            className="w-full rounded-xl border border-slate-200 bg-white p-3 text-sm text-slate-900 focus:border-primary-500 focus:outline-none sm:p-3.5 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
          />
          <div className="flex justify-end">
            <button
              type="button"
              disabled={!answer.trim() || submitting}
              onClick={handleSubmitAnswer}
              className="btn-primary flex w-full items-center justify-center gap-2 rounded-xl px-6 py-2.5 text-sm font-medium disabled:opacity-50 sm:w-auto"
            >
              {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
              {currentQ.questionIndex + 1 >= state.totalQuestions ? '提交并完成面试' : '提交本题，进入下一题'}
            </button>
          </div>
        </div>
      )}

      <ConfirmDialog
        open={confirmCompleteOpen}
        title="确认提前交卷"
        message="提前交卷后未作答题目将按未完成处理，交卷后不可继续作答。确定交卷吗？"
        confirmText="确认交卷"
        confirmVariant="warning"
        loading={completing}
        onConfirm={handleCompleteEarly}
        onCancel={() => setConfirmCompleteOpen(false)}
      />

      {reminderToast}
      {state.watermarkText && <ProctorWatermark text={state.watermarkText} />}
    </div>
  );
}
