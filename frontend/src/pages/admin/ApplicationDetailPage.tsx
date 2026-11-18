import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowLeft,
  Calendar,
  CheckCircle2,
  Clock,
  Download,
  FileText,
  Loader2,
  Mail,
  MessageSquare,
  Phone,
  ShieldAlert,
  Sparkles,
  UserCheck,
  XCircle,
} from 'lucide-react';
import { applicationApi } from '../../api/application';
import { historyApi, type InterviewDetail } from '../../api/history';
import { getErrorMessage } from '../../api/request';
import { voiceInterviewApi, type InterviewMessage } from '../../api/voiceInterview';
import InterviewDetailPanel from '../../components/InterviewDetailPanel';
import { ROUTES } from '../../constants/routes';
import { getStoredUser } from '../../utils/authStorage';
import { formatDateTime } from '../../utils/date';
import { INTERVIEW_MODE_LABELS, JOB_DIFFICULTY_LABELS } from '../../types/job';
import {
  APPLICATION_STATUS_BADGES,
  APPLICATION_STATUS_LABELS,
  EMAIL_LOG_STATUS_LABELS,
  PROCTOR_EVENT_LABELS,
  PROCTOR_RISK_BADGES,
  PROCTOR_RISK_LABELS,
  WRITTEN_QUESTION_TYPE_LABELS,
  WRITTEN_TEST_STATUS_LABELS,
  type ApplicationDetail,
  type ApplicationStatus,
  type ProctorEventItem,
  type ProctorEventType,
} from '../../types/recruitment';

type DetailTab = 'overview' | 'resume' | 'interview' | 'timeline';

function formatDuration(ms: number | null | undefined): string {
  if (ms == null) return '-';
  const totalSeconds = Math.round(ms / 1000);
  if (totalSeconds < 60) return `${totalSeconds} 秒`;
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return seconds === 0 ? `${minutes} 分钟` : `${minutes} 分 ${seconds} 秒`;
}

function describeEvent(ev: ProctorEventItem): string {
  const parts: string[] = [];
  if (ev.questionIndex != null) parts.push(`第 ${ev.questionIndex + 1} 题`);
  if (ev.durationMs != null) parts.push(`持续 ${formatDuration(ev.durationMs)}`);
  if (ev.chars != null) parts.push(`${ev.chars} 字`);
  if (ev.detail) parts.push(ev.detail);
  const label = PROCTOR_EVENT_LABELS[ev.type] ?? ev.type;
  return parts.length > 0 ? `${label}（${parts.join('，')}）` : label;
}

const NON_TERMINAL_REJECTABLE: ApplicationStatus[] = [
  'SUBMITTED',
  'SCREENING',
  'INTERVIEW_INVITED',
  'INTERVIEWING',
  'AI_PASSED',
  'HR_APPROVED',
  'OFFLINE_SCHEDULED',
  'OFFLINE_CONFIRMED',
];

function toInputDateTime(value: string | null | undefined): string {
  if (!value) return '';
  return value.slice(0, 16);
}

function toApiDateTime(value: string): string {
  return value.length === 16 ? `${value}:00` : value;
}

export default function ApplicationDetailPage() {
  const { applicationId } = useParams<{ applicationId: string }>();
  const currentUser = getStoredUser();
  const canWrite = currentUser?.role === 'ADMIN' || currentUser?.role === 'HR';

  const [detail, setDetail] = useState<ApplicationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [tab, setTab] = useState<DetailTab>('overview');

  // HR 审核表单
  const [hrComment, setHrComment] = useState('');
  const [reviewing, setReviewing] = useState(false);
  const [downloading, setDownloading] = useState(false);

  // 线下面试表单
  const [offlineTime, setOfflineTime] = useState('');
  const [offlineDuration, setOfflineDuration] = useState('60');
  const [offlineLocation, setOfflineLocation] = useState('');
  const [offlineInterviewer, setOfflineInterviewer] = useState('');
  const [offlineRound, setOfflineRound] = useState('1');
  const [offlineNotes, setOfflineNotes] = useState('');
  const [scheduling, setScheduling] = useState(false);

  // 在线面试详情（懒加载）
  const [interviewDetail, setInterviewDetail] = useState<InterviewDetail | null>(null);
  const [voiceMessages, setVoiceMessages] = useState<InterviewMessage[]>([]);
  const [interviewLoading, setInterviewLoading] = useState(false);
  const [interviewError, setInterviewError] = useState<string | null>(null);

  const showNotice = (msg: string) => {
    setNotice(msg);
    window.setTimeout(() => setNotice(null), 3000);
  };

  const syncOfflineForm = (data: ApplicationDetail) => {
    if (data.offlineInterview) {
      setOfflineTime(toInputDateTime(data.offlineInterview.interviewTime));
      setOfflineDuration(String(data.offlineInterview.durationMinutes ?? 60));
      setOfflineLocation(data.offlineInterview.location ?? '');
      setOfflineInterviewer(data.offlineInterview.interviewer ?? '');
      setOfflineRound(String(data.offlineInterview.roundNumber ?? 1));
      setOfflineNotes(data.offlineInterview.notes ?? '');
    }
  };

  const loadDetail = useCallback(async () => {
    const id = Number(applicationId);
    if (!applicationId || Number.isNaN(id)) {
      setError('投递记录不存在');
      setLoading(false);
      return;
    }
    setLoading(true);
    setError(null);
    try {
      const data = await applicationApi.detail(id);
      setDetail(data);
      setHrComment(data.hrComment ?? '');
      syncOfflineForm(data);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [applicationId]);

  useEffect(() => {
    loadDetail();
  }, [loadDetail]);

  // 切换到在线面试标签时加载题目作答 / 语音评估详情
  useEffect(() => {
    if (tab !== 'interview' || !detail) return;
    if (!detail.interviewSessionId && !detail.voiceSessionId) return;

    setInterviewLoading(true);
    setInterviewError(null);

    if (detail.interviewSessionId) {
      historyApi
        .getInterviewDetail(detail.interviewSessionId)
        .then(res => setInterviewDetail(res))
        .catch(err => setInterviewError(getErrorMessage(err)))
        .finally(() => setInterviewLoading(false));
      return;
    }

    if (detail.voiceSessionId) {
      const sid = detail.voiceSessionId;
      Promise.all([
        voiceInterviewApi.getEvaluation(sid).catch(() => null),
        voiceInterviewApi.getMessages(sid).catch(() => []),
      ])
        .then(([evalRes, msgs]) => {
          setVoiceMessages(msgs);
          if (evalRes?.evaluation) {
            const ev = evalRes.evaluation;
            setInterviewDetail({
              id: 0,
              sessionId: String(sid),
              totalQuestions: ev.totalQuestions,
              status: 'COMPLETED',
              overallScore: ev.overallScore,
              overallFeedback: ev.overallFeedback,
              createdAt: detail.interviewStartedAt ?? '',
              completedAt: detail.interviewCompletedAt ?? '',
              strengths: ev.strengths,
              improvements: ev.improvements,
              answers: ev.answers.map(a => ({
                questionIndex: a.questionIndex,
                question: a.question,
                category: a.category,
                userAnswer: a.userAnswer,
                score: a.score,
                feedback: a.feedback,
                referenceAnswer: a.referenceAnswer ?? undefined,
                keyPoints: a.keyPoints ?? undefined,
                answeredAt: '',
              })),
            });
          } else {
            setInterviewDetail(null);
          }
        })
        .catch(err => setInterviewError(getErrorMessage(err)))
        .finally(() => setInterviewLoading(false));
    }
  }, [tab, detail]);

  const handleDownloadResume = async () => {
    if (!detail) return;
    setDownloading(true);
    try {
      const blob = await applicationApi.downloadResume(detail.id);
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = detail.resumeFilename || `resume-${detail.id}.pdf`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setDownloading(false);
    }
  };

  const handleReview = async (approve: boolean) => {
    if (!detail) return;
    setReviewing(true);
    setError(null);
    try {
      const updated = approve
        ? await applicationApi.approve(detail.id, hrComment.trim() || undefined)
        : await applicationApi.reject(detail.id, hrComment.trim() || undefined);
      setDetail(updated);
      showNotice(approve ? '已审核通过，请继续安排线下面试' : '已标记为未通过，通知邮件已加入发送队列');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setReviewing(false);
    }
  };

  const handleScheduleOffline = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!detail) return;
    if (!offlineTime || !offlineLocation.trim()) {
      setError('请填写线下面试时间与地点');
      return;
    }
    setScheduling(true);
    setError(null);
    try {
      const updated = await applicationApi.scheduleOffline(detail.id, {
        interviewTime: toApiDateTime(offlineTime),
        durationMinutes: Number(offlineDuration) || 60,
        location: offlineLocation.trim(),
        interviewer: offlineInterviewer.trim() || null,
        roundNumber: Number(offlineRound) || 1,
        notes: offlineNotes.trim() || null,
      });
      setDetail(updated);
      syncOfflineForm(updated);
      showNotice('线下面试邀约已保存，邀请邮件与日历附件已加入发送队列');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setScheduling(false);
    }
  };

  const canScheduleOffline = useMemo(() => {
    if (!detail || !canWrite) return false;
    return (
      detail.status === 'HR_APPROVED'
      || detail.status === 'OFFLINE_SCHEDULED'
      || detail.status === 'OFFLINE_CONFIRMED'
    );
  }, [detail, canWrite]);

  const proctorTotal = useMemo(() => {
    if (!detail) return 0;
    return Object.values(detail.proctorCounts ?? {}).reduce((sum, n) => sum + (n ?? 0), 0);
  }, [detail]);

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  if (!detail) {
    return (
      <div className="dark-card mx-auto max-w-xl py-16 text-center">
        <p className="text-slate-600 dark:text-slate-300">{error || '投递记录不存在'}</p>
        <Link to={ROUTES.adminApplications} className="btn-primary mt-4 inline-block rounded-xl px-4 py-2 text-sm">
          返回投递列表
        </Link>
      </div>
    );
  }

  const inputClass =
    'w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100';

  return (
    <div className="mx-auto max-w-6xl space-y-6 pb-16">
      {/* 头部信息 */}
      <div className="flex flex-wrap items-start justify-between gap-4">
        <div className="flex items-start gap-3">
          <Link
            to={ROUTES.adminApplications}
            className="mt-1 rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-800"
          >
            <ArrowLeft className="h-5 w-5" />
          </Link>
          <div>
            <div className="flex flex-wrap items-center gap-3">
              <h1 className="text-2xl font-bold text-slate-900 dark:text-white">{detail.candidateName}</h1>
              <span
                className={`inline-flex rounded-full px-3 py-0.5 text-xs font-medium ${APPLICATION_STATUS_BADGES[detail.status]}`}
              >
                {APPLICATION_STATUS_LABELS[detail.status]}
              </span>
              {detail.candidateLevel && (
                <span
                  data-testid="candidate-level-badge"
                  className="inline-flex rounded-full bg-primary-50 px-3 py-0.5 text-xs font-medium text-primary-700 dark:bg-primary-900/30 dark:text-primary-300"
                >
                  自选定位：{JOB_DIFFICULTY_LABELS[detail.candidateLevel]}
                  {detail.jobDifficulty && detail.jobDifficulty !== detail.candidateLevel
                    ? `（岗位建议：${JOB_DIFFICULTY_LABELS[detail.jobDifficulty]}）`
                    : ''}
                </span>
              )}
              {detail.proctorRisk && (
                <span
                  data-testid="proctor-risk-badge"
                  className={`inline-flex rounded-full px-3 py-0.5 text-xs font-medium ${PROCTOR_RISK_BADGES[detail.proctorRisk.level]}`}
                >
                  作答行为：{PROCTOR_RISK_LABELS[detail.proctorRisk.level]}（{detail.proctorRisk.score} 分）
                </span>
              )}
            </div>
            <div className="mt-1.5 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-slate-500 dark:text-slate-400">
              <span>投递岗位：{detail.jobTitle}</span>
              {detail.candidateEmail && (
                <span className="flex items-center gap-1">
                  <Mail className="h-3.5 w-3.5" />
                  {detail.candidateEmail}
                </span>
              )}
              {detail.candidatePhone && (
                <span className="flex items-center gap-1">
                  <Phone className="h-3.5 w-3.5" />
                  {detail.candidatePhone}
                </span>
              )}
              <span className="flex items-center gap-1">
                <Clock className="h-3.5 w-3.5" />
                投递于 {formatDateTime(detail.submittedAt)}
              </span>
            </div>
          </div>
        </div>

        <button
          type="button"
          onClick={handleDownloadResume}
          disabled={downloading}
          className="btn-secondary flex items-center gap-2 rounded-xl px-4 py-2 text-sm disabled:opacity-60"
        >
          {downloading ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
          下载原简历
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

      {/* 候选人申请改期提醒 */}
      {detail.offlineInterview?.confirmStatus === 'RESCHEDULE_REQUESTED' && (
        <div className="flex items-start gap-3 rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm text-amber-800 dark:border-amber-800 dark:bg-amber-900/20 dark:text-amber-200">
          <AlertTriangle className="mt-0.5 h-5 w-5 shrink-0" />
          <div>
            <div className="font-semibold">候选人申请调整线下面试时间</div>
            <div className="mt-1">{detail.offlineInterview.candidateNote || '未填写说明'}</div>
          </div>
        </div>
      )}

      {/* HR 操作面板 */}
      {canWrite && detail.status === 'AI_PASSED' && (
        <section className="dark-card space-y-4 border border-amber-200 p-6 dark:border-amber-800/60">
          <div className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-white">
            <UserCheck className="h-5 w-5 text-amber-500" />
            HR 人工审核
          </div>
          <textarea
            className={inputClass}
            rows={2}
            maxLength={2000}
            value={hrComment}
            onChange={e => setHrComment(e.target.value)}
            placeholder="填写审核意见或面试建议（选填，仅内部可见）"
          />
          <div className="flex flex-wrap gap-3">
            <button
              type="button"
              disabled={reviewing}
              onClick={() => handleReview(true)}
              className="btn-primary flex items-center gap-1.5 rounded-xl px-5 py-2 text-sm disabled:opacity-60"
            >
              <CheckCircle2 className="h-4 w-4" />
              审核通过（进入线下邀约）
            </button>
            <button
              type="button"
              disabled={reviewing}
              onClick={() => handleReview(false)}
              className="flex items-center gap-1.5 rounded-xl bg-rose-600 px-5 py-2 text-sm font-medium text-white hover:bg-rose-700 disabled:opacity-60"
            >
              <XCircle className="h-4 w-4" />
              审核不通过（发送结果通知）
            </button>
          </div>
        </section>
      )}

      {/* 安排 / 调整线下面试 */}
      {canScheduleOffline && (
        <section className="dark-card space-y-4 p-6">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-white">
              <Calendar className="h-5 w-5 text-primary-500" />
              {detail.offlineInterview ? '线下面试安排（修改后将重新发送日历邀请）' : '安排线下面试并发送邀约邮件'}
            </div>
            {detail.offlineInterview?.confirmStatus && (
              <span className="text-xs text-slate-500 dark:text-slate-400">
                候选人确认状态：
                {detail.offlineInterview.confirmStatus === 'CONFIRMED'
                  ? '已确认参加'
                  : detail.offlineInterview.confirmStatus === 'RESCHEDULE_REQUESTED'
                  ? '申请改期'
                  : '待确认'}
              </span>
            )}
          </div>

          <form onSubmit={handleScheduleOffline} className="grid gap-4 md:grid-cols-3">
            <div>
              <label className="mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300">面试时间 *</label>
              <input
                type="datetime-local"
                required
                value={offlineTime}
                onChange={e => setOfflineTime(e.target.value)}
                className={inputClass}
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300">预计时长（分钟） *</label>
              <input
                type="number"
                min={10}
                max={480}
                required
                value={offlineDuration}
                onChange={e => setOfflineDuration(e.target.value)}
                className={inputClass}
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300">轮次</label>
              <input
                type="number"
                min={1}
                max={20}
                value={offlineRound}
                onChange={e => setOfflineRound(e.target.value)}
                className={inputClass}
              />
            </div>
            <div className="md:col-span-2">
              <label className="mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300">线下面试地点 *</label>
              <input
                required
                maxLength={512}
                value={offlineLocation}
                onChange={e => setOfflineLocation(e.target.value)}
                placeholder="如：北京市朝阳区 XX 大厦 12 层前台"
                className={inputClass}
              />
            </div>
            <div>
              <label className="mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300">面试官</label>
              <input
                maxLength={255}
                value={offlineInterviewer}
                onChange={e => setOfflineInterviewer(e.target.value)}
                placeholder="如：张工"
                className={inputClass}
              />
            </div>
            <div className="md:col-span-3">
              <label className="mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300">携带材料 / 备注说明</label>
              <input
                maxLength={2000}
                value={offlineNotes}
                onChange={e => setOfflineNotes(e.target.value)}
                placeholder="如：请携带身份证与纸质简历提前 10 分钟到达"
                className={inputClass}
              />
            </div>
            <div className="flex items-center justify-between md:col-span-3">
              <button
                type="submit"
                disabled={scheduling}
                className="btn-primary flex items-center gap-2 rounded-xl px-5 py-2 text-sm disabled:opacity-60"
              >
                {scheduling ? <Loader2 className="h-4 w-4 animate-spin" /> : <Mail className="h-4 w-4" />}
                {detail.offlineInterview ? '更新安排并重发邀约邮件' : '保存并发送线下面试邀请'}
              </button>

              {NON_TERMINAL_REJECTABLE.includes(detail.status) && (
                <button
                  type="button"
                  disabled={reviewing}
                  onClick={() => handleReview(false)}
                  className="text-xs text-rose-600 hover:underline dark:text-rose-400"
                >
                  标记为不通过
                </button>
              )}
            </div>
          </form>
        </section>
      )}

      {/* 标签页导航 */}
      <div className="flex flex-wrap gap-2 border-b border-slate-200 pb-2 dark:border-slate-700">
        {[
          { key: 'overview', label: 'AI 审核与风控', icon: Sparkles },
          { key: 'resume', label: '简历原文', icon: FileText },
          { key: 'interview', label: '在线面试记录', icon: MessageSquare },
          { key: 'timeline', label: '流转日志与邮件', icon: Clock },
        ].map(item => (
          <button
            key={item.key}
            type="button"
            onClick={() => setTab(item.key as DetailTab)}
            className={`flex items-center gap-1.5 rounded-xl px-4 py-2 text-sm font-medium transition ${
              tab === item.key
                ? 'bg-primary-500 text-white'
                : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'
            }`}
          >
            <item.icon className="h-4 w-4" />
            {item.label}
          </button>
        ))}
      </div>

      {/* 标签内容：1. AI 审核与风控 */}
      {tab === 'overview' && (
        <div className="grid gap-6 lg:grid-cols-2">
          {/* AI 初筛 */}
          <div className="dark-card space-y-3 p-6">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-semibold text-slate-900 dark:text-white">AI 简历初筛</h3>
              <span className="text-sm font-semibold text-primary-600 dark:text-primary-400">
                {detail.screeningScore != null ? `${detail.screeningScore} 分` : '未启用 / 未执行'}
                {detail.screeningThreshold != null ? `（通过线 ${detail.screeningThreshold}）` : ''}
              </span>
            </div>
            {detail.screeningResult ? (
              <div className="space-y-3 text-sm text-slate-700 dark:text-slate-300">
                <p>{detail.screeningResult.summary}</p>
                {detail.screeningResult.strengths?.length > 0 && (
                  <div>
                    <div className="font-medium text-emerald-700 dark:text-emerald-400">亮点匹配</div>
                    <ul className="mt-1 list-disc pl-5 space-y-0.5">
                      {detail.screeningResult.strengths.map((s, i) => (
                        <li key={i}>{s}</li>
                      ))}
                    </ul>
                  </div>
                )}
                {detail.screeningResult.risks?.length > 0 && (
                  <div>
                    <div className="font-medium text-amber-700 dark:text-amber-400">潜在不足 / 风险</div>
                    <ul className="mt-1 list-disc pl-5 space-y-0.5">
                      {detail.screeningResult.risks.map((r, i) => (
                        <li key={i}>{r}</li>
                      ))}
                    </ul>
                  </div>
                )}
              </div>
            ) : (
              <p className="text-sm text-slate-400">暂无初筛分析详情</p>
            )}
          </div>

          {/* AI 综合决策 */}
          <div className="dark-card space-y-3 p-6">
            <div className="flex items-center justify-between">
              <h3 className="text-base font-semibold text-slate-900 dark:text-white">AI 在线面试审核</h3>
              <span className="text-sm font-semibold text-primary-600 dark:text-primary-400">
                {detail.aiScore != null ? `${detail.aiScore} 分` : '待完成'}
                {detail.aiPassThreshold != null ? `（通过线 ${detail.aiPassThreshold}）` : ''}
              </span>
            </div>

            {detail.interviewMode && (
              <div className="text-xs text-slate-500 dark:text-slate-400">
                面试形式：{INTERVIEW_MODE_LABELS[detail.interviewMode]}
              </div>
            )}

            {detail.aiReview?.written && (
              <div
                data-testid="written-weighted-summary"
                className="rounded-lg bg-primary-50/70 px-3 py-2 text-xs text-primary-800 dark:bg-primary-900/25 dark:text-primary-200"
              >
                综合分 {detail.aiReview.written.finalScore} 分 = 笔试 {detail.aiReview.written.writtenScore} 分 ×{' '}
                {detail.aiReview.written.weight}% + 面试 {detail.aiReview.written.interviewScore} 分 ×{' '}
                {100 - detail.aiReview.written.weight}%
              </div>
            )}

            {detail.aiReview ? (
              <div className="space-y-3 text-sm text-slate-700 dark:text-slate-300">
                {detail.aiReview.note && (
                  <div className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                    {detail.aiReview.note}
                  </div>
                )}
                {detail.aiReview.llm && (
                  <>
                    <p>{detail.aiReview.llm.summary}</p>
                    {detail.aiReview.llm.reasons?.length > 0 && (
                      <div>
                        <div className="font-medium text-slate-800 dark:text-slate-200">判断依据</div>
                        <ul className="mt-1 list-disc pl-5 space-y-0.5">
                          {detail.aiReview.llm.reasons.map((r, i) => (
                            <li key={i}>{r}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                    {detail.aiReview.llm.suggestedQuestions?.length > 0 && (
                      <div>
                        <div className="font-medium text-indigo-700 dark:text-indigo-300">建议线下面试追问</div>
                        <ul className="mt-1 list-disc pl-5 space-y-0.5">
                          {detail.aiReview.llm.suggestedQuestions.map((q, i) => (
                            <li key={i}>{q}</li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </>
                )}
              </div>
            ) : (
              <p className="text-sm text-slate-400">候选人完成在线面试后自动生成综合审核结论</p>
            )}

            {detail.hrReviewedAt && (
              <div className="border-t border-slate-100 pt-3 text-xs text-slate-500 dark:border-slate-800 dark:text-slate-400">
                HR 审核人：{detail.hrReviewerName || 'HR'} · {formatDateTime(detail.hrReviewedAt)}
                {detail.hrComment && <div className="mt-1 text-slate-700 dark:text-slate-300">意见：{detail.hrComment}</div>}
              </div>
            )}
          </div>

          {/* 在线笔试详情 */}
          {detail.writtenTest && (
            <div className="dark-card space-y-4 p-6 lg:col-span-2" data-testid="written-detail-card">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h3 className="text-base font-semibold text-slate-900 dark:text-white">
                    在线笔试（选择题 / 填空题）
                  </h3>
                  <p className="text-xs text-slate-500 dark:text-slate-400">
                    状态：{WRITTEN_TEST_STATUS_LABELS[detail.writtenTest.status]}
                    {' · '}权重 {detail.writtenTest.weight}%
                    {detail.writtenTest.submittedAt
                      ? ` · 交卷于 ${formatDateTime(detail.writtenTest.submittedAt)}`
                      : ''}
                  </p>
                </div>
                <span className="text-sm font-semibold text-primary-600 dark:text-primary-400">
                  {detail.writtenTest.score != null
                    ? `${detail.writtenTest.score} 分（答对 ${detail.writtenTest.correctCount ?? 0} / ${detail.writtenTest.totalCount} 题）`
                    : `共 ${detail.writtenTest.totalCount} 题`}
                </span>
              </div>

              <div className="max-h-96 space-y-3 overflow-y-auto pr-1">
                {detail.writtenTest.items.map(item => {
                  const answered = item.candidateAnswer && item.candidateAnswer.length > 0;
                  return (
                    <div
                      key={item.index}
                      className="space-y-2 rounded-xl border border-slate-100 bg-slate-50/70 p-4 text-xs dark:border-slate-800 dark:bg-slate-800/50"
                    >
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-semibold text-slate-900 dark:text-white">
                            第 {item.index + 1} 题
                          </span>
                          <span className="rounded bg-slate-200/70 px-1.5 py-0.5 text-slate-700 dark:bg-slate-700 dark:text-slate-200">
                            {WRITTEN_QUESTION_TYPE_LABELS[item.type]}
                          </span>
                          {item.category && <span className="text-slate-400">{item.category}</span>}
                          {item.judgedBy === 'AI' && (
                            <span className="rounded bg-indigo-50 px-1.5 py-0.5 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300">
                              AI 语义判分
                            </span>
                          )}
                        </div>
                        {item.correct != null && (
                          <span
                            className={`rounded-full px-2.5 py-0.5 font-medium ${
                              item.correct
                                ? 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300'
                                : 'bg-rose-50 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300'
                            }`}
                          >
                            {item.correct ? '正确' : answered ? '错误' : '未作答'}
                          </span>
                        )}
                      </div>

                      <div className="text-sm text-slate-800 dark:text-slate-100">{item.question}</div>

                      {item.type !== 'FILL_BLANK' && item.options.length > 0 && (
                        <div className="grid gap-1 sm:grid-cols-2 text-slate-600 dark:text-slate-300">
                          {item.options.map((opt, idx) => {
                            const letter = String.fromCharCode(65 + idx);
                            const isCorrectOpt = item.correctAnswers.includes(letter);
                            const isChosen = item.candidateAnswer.includes(letter);
                            return (
                              <div
                                key={letter}
                                className={`rounded px-2 py-1 ${
                                  isCorrectOpt
                                    ? 'bg-emerald-50/80 text-emerald-800 dark:bg-emerald-900/25 dark:text-emerald-200'
                                    : isChosen
                                      ? 'bg-rose-50/80 text-rose-800 dark:bg-rose-900/25 dark:text-rose-200'
                                      : ''
                                }`}
                              >
                                {letter}. {opt}
                              </div>
                            );
                          })}
                        </div>
                      )}

                      <div className="flex flex-wrap gap-x-4 gap-y-1 text-slate-600 dark:text-slate-300">
                        <span>
                          候选人作答：
                          <strong className="text-slate-900 dark:text-white">
                            {answered ? item.candidateAnswer.join('、') : '（未作答）'}
                          </strong>
                        </span>
                        <span>
                          标准答案：
                          <strong className="text-emerald-700 dark:text-emerald-400">
                            {item.correctAnswers.join(' / ')}
                          </strong>
                        </span>
                      </div>

                      {(item.reason || item.explanation) && (
                        <div className="text-slate-500 dark:text-slate-400">
                          {item.reason && <span>判分说明：{item.reason}　</span>}
                          {item.explanation && <span>解析：{item.explanation}</span>}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>
          )}

          {/* 防作弊行为记录 */}
          <div className="dark-card space-y-3 p-6 lg:col-span-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h3 className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-white">
                <ShieldAlert className="h-4 w-4 text-amber-500" />
                作答行为分析（共 {proctorTotal} 条记录）
              </h3>
              {detail.proctorRisk && (
                <span
                  className={`rounded-full px-3 py-0.5 text-xs font-medium ${PROCTOR_RISK_BADGES[detail.proctorRisk.level]}`}
                >
                  {PROCTOR_RISK_LABELS[detail.proctorRisk.level]} · {detail.proctorRisk.score} 分
                </span>
              )}
            </div>
            <p className="text-xs text-slate-400">
              以下为候选人作答过程中的无感记录，仅作为辅助参考，不会自动淘汰候选人；短暂切换（少于 5 秒）不计入风险。
            </p>
            {(detail.proctorRisk?.findings.length ?? 0) > 0 && (
              <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700 dark:text-slate-300" data-testid="proctor-findings">
                {detail.proctorRisk!.findings.map((f, i) => (
                  <li key={i}>{f}</li>
                ))}
              </ul>
            )}
            {proctorTotal === 0 ? (
              <p className="text-sm text-slate-400">未检测到切屏、失焦或粘贴等异常行为</p>
            ) : (
              <div className="space-y-3">
                <div className="flex flex-wrap gap-2">
                  {(Object.entries(detail.proctorCounts) as [ProctorEventType, number][]).map(([type, count]) => (
                    <span
                      key={type}
                      className="rounded-full bg-amber-50 px-3 py-1 text-xs font-medium text-amber-800 dark:bg-amber-900/30 dark:text-amber-200"
                    >
                      {PROCTOR_EVENT_LABELS[type] ?? type}：{count} 次
                    </span>
                  ))}
                </div>
                <div className="max-h-48 overflow-y-auto text-xs text-slate-500 dark:text-slate-400 space-y-1">
                  {detail.proctorEvents.map(ev => (
                    <div key={ev.id}>
                      {formatDateTime(ev.occurredAt)} · {describeEvent(ev)}
                    </div>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      )}

      {/* 标签内容：2. 简历原文 */}
      {tab === 'resume' && (
        <div className="dark-card p-6">
          <div className="mb-3 flex items-center justify-between">
            <h3 className="text-base font-semibold text-slate-900 dark:text-white">
              简历解析文本 {detail.resumeFilename ? `（${detail.resumeFilename}）` : ''}
            </h3>
          </div>
          <pre className="max-h-[640px] overflow-y-auto whitespace-pre-wrap rounded-xl bg-slate-50 p-4 font-sans text-sm leading-relaxed text-slate-700 dark:bg-slate-800/70 dark:text-slate-200">
            {detail.resumeText || '暂无解析文本，请下载原简历查看'}
          </pre>
        </div>
      )}

      {/* 标签内容：3. 在线面试记录 */}
      {tab === 'interview' && (
        <div className="space-y-4">
          {!detail.interviewSessionId && !detail.voiceSessionId ? (
            <div className="dark-card py-14 text-center text-sm text-slate-400">
              候选人尚未开始在线面试
            </div>
          ) : interviewLoading ? (
            <div className="flex justify-center py-16">
              <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
            </div>
          ) : interviewError ? (
            <div className="dark-card p-6 text-sm text-red-600 dark:text-red-400">{interviewError}</div>
          ) : (
            <>
              {(detail.answerTelemetry?.length ?? 0) > 0 && (
                <div className="dark-card space-y-3 p-6">
                  <h3 className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-white">
                    <ShieldAlert className="h-4 w-4 text-amber-500" />
                    逐题作答行为
                  </h3>
                  <p className="text-xs text-slate-400">
                    标红的题目存在“离开页面后大段粘贴”或“短时间大段输入”等情况，仅供参考，建议在线下面试中针对性追问。
                  </p>
                  <div className="overflow-x-auto">
                    <table className="w-full text-left text-xs">
                      <thead className="text-slate-500 dark:text-slate-400">
                        <tr className="border-b border-slate-100 dark:border-slate-800">
                          <th className="px-2 py-2 font-medium">题号</th>
                          <th className="px-2 py-2 font-medium">作答耗时</th>
                          <th className="px-2 py-2 font-medium">首次输入</th>
                          <th className="px-2 py-2 font-medium">期间离开</th>
                          <th className="px-2 py-2 font-medium">键入字数</th>
                          <th className="px-2 py-2 font-medium">粘贴字数</th>
                          <th className="px-2 py-2 font-medium">粘贴占比</th>
                          <th className="px-2 py-2 font-medium">答案字数</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.answerTelemetry!.map(t => (
                          <tr
                            key={t.questionIndex}
                            data-testid={`telemetry-row-${t.questionIndex}`}
                            className={`border-b border-slate-50 dark:border-slate-800/60 ${
                              t.flagged
                                ? 'bg-rose-50 text-rose-700 dark:bg-rose-900/20 dark:text-rose-300'
                                : 'text-slate-700 dark:text-slate-300'
                            }`}
                          >
                            <td className="px-2 py-2">第 {t.questionIndex + 1} 题</td>
                            <td className="px-2 py-2">{formatDuration(t.durationMs)}</td>
                            <td className="px-2 py-2">{formatDuration(t.firstInputMs)}</td>
                            <td className="px-2 py-2">{formatDuration(t.awayMs)}</td>
                            <td className="px-2 py-2">{t.typedChars ?? '-'}</td>
                            <td className="px-2 py-2">{t.pastedChars ?? '-'}</td>
                            <td className="px-2 py-2">
                              {t.pasteRatio == null ? '-' : `${Math.round(t.pasteRatio * 100)}%`}
                            </td>
                            <td className="px-2 py-2">{t.answerChars ?? '-'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
              {interviewDetail && <InterviewDetailPanel interview={interviewDetail} />}
              {voiceMessages.length > 0 && (
                <div className="dark-card space-y-3 p-6">
                  <h3 className="text-base font-semibold text-slate-900 dark:text-white">语音对话原始记录</h3>
                  <div className="space-y-3 max-h-[480px] overflow-y-auto text-sm">
                    {voiceMessages.map(m => (
                      <div key={m.id} className="space-y-1.5 rounded-xl bg-slate-50 p-3 dark:bg-slate-800/60">
                        {m.userRecognizedText && (
                          <div className="text-slate-700 dark:text-slate-200">
                            <span className="font-semibold text-primary-600">候选人：</span>
                            {m.userRecognizedText}
                          </div>
                        )}
                        {m.aiGeneratedText && (
                          <div className="text-slate-600 dark:text-slate-300">
                            <span className="font-semibold text-indigo-600">面试官：</span>
                            {m.aiGeneratedText}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </>
          )}
        </div>
      )}

      {/* 标签内容：4. 流转日志与邮件 */}
      {tab === 'timeline' && (
        <div className="grid gap-6 lg:grid-cols-2">
          <div className="dark-card space-y-3 p-6">
            <h3 className="text-base font-semibold text-slate-900 dark:text-white">状态流转记录</h3>
            <div className="space-y-3">
              {detail.logs.map(log => (
                <div key={log.id} className="border-l-2 border-primary-300 pl-3 text-sm dark:border-primary-700">
                  <div className="flex items-center justify-between text-xs text-slate-400">
                    <span>{log.operatorName}</span>
                    <span>{formatDateTime(log.createdAt)}</span>
                  </div>
                  <div className="mt-0.5 font-medium text-slate-800 dark:text-slate-200">
                    {log.fromStatus ? `${APPLICATION_STATUS_LABELS[log.fromStatus]} → ` : ''}
                    {APPLICATION_STATUS_LABELS[log.toStatus]}
                  </div>
                  {log.remark && <div className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">{log.remark}</div>}
                </div>
              ))}
            </div>
          </div>

          <div className="dark-card space-y-3 p-6">
            <h3 className="text-base font-semibold text-slate-900 dark:text-white">邮件通知记录</h3>
            {detail.emails.length === 0 ? (
              <p className="text-sm text-slate-400">暂无邮件记录</p>
            ) : (
              <div className="space-y-3 text-sm">
                {detail.emails.map(mail => (
                  <div key={mail.id} className="rounded-xl bg-slate-50 p-3 dark:bg-slate-800/60">
                    <div className="flex items-center justify-between">
                      <span className="font-medium text-slate-800 dark:text-slate-200">{mail.subject}</span>
                      <span className="text-xs text-slate-500">{EMAIL_LOG_STATUS_LABELS[mail.status]}</span>
                    </div>
                    <div className="mt-1 text-xs text-slate-400">
                      收件人：{mail.toAddress} · {formatDateTime(mail.sentAt ?? mail.createdAt)}
                    </div>
                    {mail.error && <div className="mt-1 text-xs text-rose-500">错误：{mail.error}</div>}
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
