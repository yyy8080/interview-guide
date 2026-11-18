import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, Navigate, useParams } from 'react-router-dom';
import {
  CheckCircle2,
  Clock,
  Loader2,
  MessageSquare,
  Mic,
  Send,
  ShieldCheck,
} from 'lucide-react';
import { candidateApi } from '../../api/candidate';
import { getErrorMessage } from '../../api/request';
import CandidateVoicePanel from '../../components/CandidateVoicePanel';
import ConfirmDialog from '../../components/ConfirmDialog';
import { ROUTES } from '../../constants/routes';
import { isCandidateAuthenticated } from '../../utils/candidateStorage';
import type { ConcreteInterviewMode } from '../../types/job';
import type {
  CandidateApplication,
  CandidateInterviewState,
  CandidateQuestion,
  ProctorEventType,
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
  const [loading, setLoading] = useState(true);
  const [starting, setStarting] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [completing, setCompleting] = useState(false);
  const [confirmCompleteOpen, setConfirmCompleteOpen] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [answer, setAnswer] = useState('');
  const [history, setHistory] = useState<QaItem[]>([]);
  const [remaining, setRemaining] = useState<number | null>(null);

  const lastProctorAtRef = useRef<Record<string, number>>({});

  const reportProctor = useCallback(
    (type: ProctorEventType, detail?: string) => {
      if (!id || Number.isNaN(id)) return;
      const now = Date.now();
      if (now - (lastProctorAtRef.current[type] ?? 0) < 3000) return;
      lastProctorAtRef.current[type] = now;
      candidateApi.recordProctor(id, type, detail).catch(() => {});
    },
    [id],
  );

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

  // 防作弊监测（仅面试进行中生效）
  const isInterviewing = state?.stage === 'INTERVIEWING' && !state.finished;
  useEffect(() => {
    if (!isInterviewing) return;

    const onVisibility = () => {
      if (document.visibilityState === 'hidden') {
        reportProctor('TAB_HIDDEN');
      }
    };
    const onBlur = () => reportProctor('WINDOW_BLUR');
    const onCopy = () => reportProctor('COPY');

    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('blur', onBlur);
    document.addEventListener('copy', onCopy);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('blur', onBlur);
      document.removeEventListener('copy', onCopy);
    };
  }, [isInterviewing, reportProctor]);

  if (!authed) {
    const redirect = applicationId ? ROUTES.careerInterview(id) : ROUTES.careerMe;
    return <Navigate to={`${ROUTES.careerLogin}?redirect=${encodeURIComponent(redirect)}`} replace />;
  }

  const handleStart = async () => {
    setStarting(true);
    setError(null);
    try {
      const started = await candidateApi.startInterview(id, chosenMode);
      setState(started);
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
      const res = await candidateApi.submitAnswer(id, currentQ.questionIndex, submittedText);
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

  // 已完成视图
  if (
    state?.finished
    || (state && state.stage !== 'INTERVIEW_PENDING' && state.stage !== 'INTERVIEWING')
    || (app.stage !== 'INTERVIEW_PENDING' && app.stage !== 'INTERVIEWING' && !isInterviewing)
  ) {
    return (
      <div className="dark-card mx-auto max-w-xl space-y-4 p-8 text-center">
        <div className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-400">
          <CheckCircle2 className="h-8 w-8" />
        </div>
        <h1 className="text-xl font-bold text-slate-900 dark:text-white">在线面试已完成</h1>
        <p className="text-sm text-slate-600 dark:text-slate-300">
          感谢您参加「{app.jobTitle}」岗位的在线面试。系统正在整理评估报告并提交 HR 复核，后续结果将通过邮件通知您。
        </p>
        <Link to={ROUTES.careerMe} className="btn-primary inline-block rounded-xl px-6 py-2.5 text-sm">
          返回我的投递
        </Link>
      </div>
    );
  }

  // 尚未开始：选择面试形式 + 须知
  if (!isInterviewing) {
    return (
      <div className="mx-auto max-w-2xl space-y-6">
        <div className="dark-card space-y-5 p-6 sm:p-8">
          <div>
            <h1 className="text-xl font-bold text-slate-900 dark:text-white">
              {app.jobTitle} · 在线 AI 面试
            </h1>
            <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
              {app.questionCount ? `预计约 ${app.questionCount} 道题目` : '智能自适应提问'}
              {app.timeLimitMinutes ? ` · 限时 ${app.timeLimitMinutes} 分钟` : ''}
            </p>
          </div>

          {error && (
            <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
              {error}
            </div>
          )}

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
                      AI 面试官语音互动提问，支持语音识别与文字核对
                    </div>
                  </div>
                </button>
              )}
            </div>
          </div>

          {/* 面试须知 */}
          <div className="rounded-xl bg-slate-50 p-4 text-xs leading-relaxed text-slate-600 dark:bg-slate-800/60 dark:text-slate-300">
            <div className="mb-1.5 flex items-center gap-1.5 font-semibold text-slate-800 dark:text-slate-200">
              <ShieldCheck className="h-4 w-4 text-primary-500" />
              诚信面试须知
            </div>
            <ul className="list-disc space-y-1 pl-5">
              <li>点击「开始面试」后立即开始计时，请确保网络通畅、环境安静。</li>
              <li>请本人独立完成作答，面试期间切换标签页、窗口失焦、复制题目或粘贴内容会被系统记录并供 HR 参考。</li>
            </ul>
          </div>

          <div className="flex justify-end gap-3">
            <Link to={ROUTES.careerMe} className="btn-secondary rounded-xl px-5 py-2.5 text-sm">
              稍后再面
            </Link>
            <button
              type="button"
              disabled={starting}
              onClick={handleStart}
              className="btn-primary flex items-center gap-2 rounded-xl px-6 py-2.5 text-sm font-medium disabled:opacity-60"
            >
              {starting && <Loader2 className="h-4 w-4 animate-spin" />}
              {starting ? '正在生成面试题目…' : '开始在线面试'}
            </button>
          </div>
        </div>
      </div>
    );
  }

  // 进行中：语音模式
  if (state.mode === 'VOICE' && state.voiceSessionId) {
    return (
      <div className="space-y-4">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h1 className="text-lg font-bold text-slate-900 dark:text-white">
            {state.jobTitle} · 语音面试进行中
          </h1>
          {remaining != null && (
            <span className="flex items-center gap-1.5 rounded-full bg-slate-100 px-3 py-1 text-xs font-semibold text-slate-700 dark:bg-slate-800 dark:text-slate-200">
              <Clock className="h-3.5 w-3.5 text-primary-500" />
              剩余 {formatSeconds(remaining)}
            </span>
          )}
        </div>

        <CandidateVoicePanel
          voiceSessionId={state.voiceSessionId}
          onEndInterview={handleEndVoice}
          ending={completing}
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
    <div className="mx-auto max-w-3xl space-y-4">
      <div className="dark-card flex flex-wrap items-center justify-between gap-3 p-4">
        <div>
          <h1 className="text-base font-bold text-slate-900 dark:text-white">{state.jobTitle} · 在线文字面试</h1>
          <p className="text-xs text-slate-500 dark:text-slate-400">
            第 {currentQ ? currentQ.questionIndex + 1 : state.currentQuestionIndex} / {state.totalQuestions} 题（{progress}%）
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
            onClick={() => setConfirmCompleteOpen(true)}
            className="text-xs font-medium text-slate-500 hover:text-rose-600 dark:text-slate-400"
          >
            提前交卷
          </button>
        </div>
      </div>

      {error && (
        <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
          {error}
        </div>
      )}

      {/* 问答对话流 */}
      <div className="dark-card max-h-[420px] space-y-4 overflow-y-auto p-6">
        {history.map(item => (
          <div key={item.questionIndex} className="space-y-3">
            <div className="rounded-2xl bg-slate-50 p-4 text-sm text-slate-800 dark:bg-slate-800/70 dark:text-slate-100">
              <div className="mb-1 flex items-center gap-2 text-xs text-primary-600 dark:text-primary-400">
                <span className="font-semibold">问题 {item.questionIndex + 1}</span>
                {item.category && <span>· {item.category}</span>}
              </div>
              <div className="whitespace-pre-wrap leading-relaxed">{item.question}</div>
            </div>

            {item.answer && (
              <div className="ml-8 rounded-2xl bg-primary-500 px-4 py-3 text-sm text-white whitespace-pre-wrap">
                {item.answer}
              </div>
            )}
          </div>
        ))}
      </div>

      {/* 作答框 */}
      {currentQ && (
        <div className="dark-card space-y-3 p-4">
          <textarea
            rows={5}
            value={answer}
            disabled={submitting}
            onChange={e => setAnswer(e.target.value)}
            onPaste={() => reportProctor('PASTE', `Q${currentQ.questionIndex + 1}`)}
            onKeyDown={e => {
              if (e.key === 'Enter' && (e.ctrlKey || e.metaKey)) {
                handleSubmitAnswer();
              }
            }}
            placeholder="请输入您的回答…（支持 Ctrl / Cmd + Enter 提交本题）"
            className="w-full rounded-xl border border-slate-200 bg-white p-3.5 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-white"
          />
          <div className="flex justify-end">
            <button
              type="button"
              disabled={!answer.trim() || submitting}
              onClick={handleSubmitAnswer}
              className="btn-primary flex items-center gap-2 rounded-xl px-6 py-2.5 text-sm font-medium disabled:opacity-50"
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
    </div>
  );
}
