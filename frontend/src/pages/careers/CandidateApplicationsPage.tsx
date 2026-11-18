import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import {
  ArrowRight,
  Calendar,
  CheckCircle2,
  Clock,
  Loader2,
  MapPin,
  RefreshCw,
} from 'lucide-react';
import { candidateApi } from '../../api/candidate';
import { getErrorMessage } from '../../api/request';
import { ROUTES } from '../../constants/routes';
import { formatDateTime } from '../../utils/date';
import { isCandidateAuthenticated } from '../../utils/candidateStorage';
import { INTERVIEW_MODE_LABELS } from '../../types/job';
import {
  CANDIDATE_STAGE_LABELS,
  type CandidateApplication,
  type CandidateStage,
} from '../../types/recruitment';

const STAGE_BADGE: Record<CandidateStage, string> = {
  SCREENING: 'bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
  INTERVIEW_PENDING: 'bg-indigo-50 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300',
  INTERVIEWING: 'bg-cyan-50 text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300',
  UNDER_REVIEW: 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
  PASSED: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300',
  OFFLINE_INVITED: 'bg-teal-50 text-teal-700 dark:bg-teal-900/30 dark:text-teal-300',
  OFFLINE_CONFIRMED: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200',
  REJECTED: 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-400',
  EXPIRED: 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400',
};

export default function CandidateApplicationsPage() {
  const authed = isCandidateAuthenticated();
  const [items, setItems] = useState<CandidateApplication[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [actingId, setActingId] = useState<number | null>(null);
  const [rescheduleOpenId, setRescheduleOpenId] = useState<number | null>(null);
  const [rescheduleNote, setRescheduleNote] = useState('');

  const loadList = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await candidateApi.listApplications();
      setItems(data);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (authed) {
      loadList();
    }
  }, [authed, loadList]);

  if (!authed) {
    return (
      <Navigate
        to={`${ROUTES.careerLogin}?redirect=${encodeURIComponent(ROUTES.careerMe)}`}
        replace
      />
    );
  }

  const showNotice = (msg: string) => {
    setNotice(msg);
    window.setTimeout(() => setNotice(null), 3000);
  };

  const handleConfirmOffline = async (id: number) => {
    setActingId(id);
    setError(null);
    try {
      const updated = await candidateApi.confirmOffline(id);
      setItems(prev => prev.map(item => (item.id === id ? updated : item)));
      showNotice('已确认按时参加线下面试');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActingId(null);
    }
  };

  const handleReschedule = async (id: number) => {
    if (!rescheduleNote.trim()) {
      setError('请填写改期原因或方便参加的时间段');
      return;
    }
    setActingId(id);
    setError(null);
    try {
      const updated = await candidateApi.requestReschedule(id, rescheduleNote.trim());
      setItems(prev => prev.map(item => (item.id === id ? updated : item)));
      setRescheduleOpenId(null);
      setRescheduleNote('');
      showNotice('已提交改期申请，HR 重新调整后将通过邮件通知您');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActingId(null);
    }
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">我的投递</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            查看简历初筛进度、完成在线 AI 面试或确认线下面试安排
          </p>
        </div>
        <button
          type="button"
          onClick={loadList}
          className="btn-secondary flex items-center gap-1.5 rounded-xl px-4 py-2 text-sm"
        >
          <RefreshCw className="h-4 w-4" />
          刷新进度
        </button>
      </div>

      {notice && (
        <div className="flex items-center gap-2 rounded-xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300">
          <CheckCircle2 className="h-4 w-4 shrink-0" />
          {notice}
        </div>
      )}

      {error && (
        <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-20">
          <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
        </div>
      ) : items.length === 0 ? (
        <div className="dark-card flex flex-col items-center gap-4 py-16 text-center">
          <p className="text-slate-500 dark:text-slate-400">您还没有投递过职位</p>
          <Link to={ROUTES.careers} className="btn-primary rounded-xl px-5 py-2 text-sm">
            浏览热招职位
          </Link>
        </div>
      ) : (
        <div className="space-y-4">
          {items.map(app => (
            <div key={app.id} className="dark-card space-y-4 p-6">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div>
                  <div className="flex flex-wrap items-center gap-2.5">
                    <h2 className="text-lg font-bold text-slate-900 dark:text-white">{app.jobTitle}</h2>
                    <span className={`rounded-full px-3 py-0.5 text-xs font-medium ${STAGE_BADGE[app.stage]}`}>
                      {CANDIDATE_STAGE_LABELS[app.stage]}
                    </span>
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500 dark:text-slate-400">
                    {app.department && <span>{app.department}</span>}
                    {app.location && (
                      <span className="flex items-center gap-1">
                        <MapPin className="h-3.5 w-3.5" />
                        {app.location}
                      </span>
                    )}
                    <span className="flex items-center gap-1">
                      <Clock className="h-3.5 w-3.5" />
                      投递于 {formatDateTime(app.submittedAt)}
                    </span>
                  </div>
                </div>

                {(app.stage === 'INTERVIEW_PENDING' || app.stage === 'INTERVIEWING') && (
                  <Link
                    to={ROUTES.careerInterview(app.id)}
                    className="btn-primary flex items-center gap-1.5 rounded-xl px-5 py-2 text-sm"
                  >
                    {app.stage === 'INTERVIEWING' ? '继续在线面试' : '开始在线面试'}
                    <ArrowRight className="h-4 w-4" />
                  </Link>
                )}
              </div>

              {/* 各阶段说明 */}
              {app.stage === 'SCREENING' && (
                <p className="text-sm text-slate-600 dark:text-slate-300">
                  我们正在对您的简历进行智能筛选，筛选通过后将自动开启在线面试并发送邮件通知。
                </p>
              )}

              {app.stage === 'INTERVIEW_PENDING' && (
                <div className="rounded-xl bg-indigo-50/70 p-4 text-sm text-indigo-900 dark:bg-indigo-900/20 dark:text-indigo-200">
                  <div>
                    可选面试形式：{app.availableModes.map(m => INTERVIEW_MODE_LABELS[m]).join(' / ')}
                    {app.questionCount ? ` · 约 ${app.questionCount} 题` : ''}
                    {app.timeLimitMinutes ? ` · 限时 ${app.timeLimitMinutes} 分钟` : ''}
                  </div>
                  {app.inviteExpiresAt && (
                    <div className="mt-1 text-xs opacity-80">
                      请在 {formatDateTime(app.inviteExpiresAt)} 前完成在线面试
                    </div>
                  )}
                </div>
              )}

              {app.stage === 'UNDER_REVIEW' && (
                <p className="text-sm text-slate-600 dark:text-slate-300">
                  您的在线面试已提交，正在进行评估与复核，审核通过后将通过邮件发送线下面试邀请。
                </p>
              )}

              {app.stage === 'PASSED' && (
                <p className="text-sm text-emerald-700 dark:text-emerald-300">
                  恭喜您通过线上审核！HR 正在为您安排线下面试时间，请留意邮箱通知。
                </p>
              )}

              {app.stage === 'REJECTED' && (
                <p className="text-sm text-slate-500 dark:text-slate-400">
                  感谢您的关注与投入，经综合评估本次未能进入下一环节，祝您求职顺利。
                </p>
              )}

              {app.stage === 'EXPIRED' && (
                <p className="text-sm text-slate-500 dark:text-slate-400">
                  在线面试邀请已超过有效期。
                </p>
              )}

              {/* 线下面试卡片 */}
              {app.offlineInterview && (app.stage === 'OFFLINE_INVITED' || app.stage === 'OFFLINE_CONFIRMED') && (
                <div className="space-y-3 rounded-xl border border-teal-200 bg-teal-50/50 p-4 dark:border-teal-800 dark:bg-teal-900/20">
                  <div className="flex items-center gap-2 text-sm font-semibold text-teal-900 dark:text-teal-200">
                    <Calendar className="h-4 w-4" />
                    线下面试安排
                  </div>
                  <div className="grid gap-1.5 text-sm text-slate-700 sm:grid-cols-2 dark:text-slate-200">
                    <div>
                      时间：{formatDateTime(app.offlineInterview.interviewTime)}
                      {app.offlineInterview.durationMinutes ? `（约 ${app.offlineInterview.durationMinutes} 分钟）` : ''}
                    </div>
                    {app.offlineInterview.location && <div>地点：{app.offlineInterview.location}</div>}
                    {app.offlineInterview.interviewer && <div>面试官：{app.offlineInterview.interviewer}</div>}
                    {app.offlineInterview.notes && <div className="sm:col-span-2">备注：{app.offlineInterview.notes}</div>}
                  </div>

                  {app.offlineInterview.confirmStatus === 'RESCHEDULE_REQUESTED' && (
                    <div className="rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-800 dark:bg-amber-900/30 dark:text-amber-200">
                      已提交改期申请：{app.offlineInterview.candidateNote}（HR 调整后将重新发送通知）
                    </div>
                  )}

                  <div className="flex flex-wrap gap-2 pt-1">
                    {app.stage === 'OFFLINE_INVITED' && app.offlineInterview.confirmStatus !== 'CONFIRMED' && (
                      <button
                        type="button"
                        disabled={actingId === app.id}
                        onClick={() => handleConfirmOffline(app.id)}
                        className="btn-primary rounded-xl px-4 py-1.5 text-xs"
                      >
                        确认按时参加
                      </button>
                    )}
                    <button
                      type="button"
                      onClick={() => {
                        setRescheduleOpenId(rescheduleOpenId === app.id ? null : app.id);
                        setRescheduleNote(app.offlineInterview?.candidateNote ?? '');
                      }}
                      className="btn-secondary rounded-xl px-4 py-1.5 text-xs"
                    >
                      申请调整时间
                    </button>
                  </div>

                  {rescheduleOpenId === app.id && (
                    <div className="flex flex-wrap gap-2 pt-2">
                      <input
                        value={rescheduleNote}
                        onChange={e => setRescheduleNote(e.target.value)}
                        maxLength={500}
                        placeholder="请填写改期原因及您方便参加的日期时间段"
                        className="min-w-[220px] flex-1 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-xs dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                      />
                      <button
                        type="button"
                        disabled={actingId === app.id}
                        onClick={() => handleReschedule(app.id)}
                        className="btn-primary rounded-xl px-4 py-1.5 text-xs"
                      >
                        提交申请
                      </button>
                    </div>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
