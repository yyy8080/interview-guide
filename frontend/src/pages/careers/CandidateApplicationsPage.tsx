import { useCallback, useEffect, useState } from 'react';
import { Link, Navigate } from 'react-router-dom';
import {
  AlertTriangle,
  ArrowRight,
  Calendar,
  CheckCircle2,
  Clock,
  Loader2,
  MapPin,
  RefreshCw,
  Upload,
} from 'lucide-react';
import { candidateApi } from '../../api/candidate';
import { getErrorMessage } from '../../api/request';
import CandidateAssessmentFeedbackCard from '../../components/CandidateAssessmentFeedbackCard';
import CandidateLevelSelector from '../../components/CandidateLevelSelector';
import CandidateTrackSelector from '../../components/CandidateTrackSelector';
import ConfirmDialog from '../../components/ConfirmDialog';
import AiWaitingOverlay from '../../components/waiting/AiWaitingOverlay';
import AiWaitingPanel from '../../components/waiting/AiWaitingPanel';
import { EVALUATION_TIPS, GENERAL_WAITING_TIPS } from '../../components/waiting/waitingTips';
import { ROUTES } from '../../constants/routes';
import { formatDateTime } from '../../utils/date';
import { isCandidateAuthenticated } from '../../utils/candidateStorage';
import { INTERVIEW_MODE_LABELS, JOB_DIFFICULTY_LABELS, type JobDifficulty } from '../../types/job';
import {
  AI_EXPERIENCE_LABELS,
  CANDIDATE_STAGE_LABELS,
  CANDIDATE_TRACK_LABELS,
  inferDefaultCandidateTrack,
  type CandidateApplication,
  type CandidateStage,
  type CandidateTrack,
} from '../../types/recruitment';

/** AI 初筛 / 评估进行中的静默轮询间隔与上限（约 3 分钟） */
const POLL_INTERVAL_MS = 6000;
const POLL_MAX_ATTEMPTS = 30;

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

  const [resubmitOpenId, setResubmitOpenId] = useState<number | null>(null);
  const [resubmitFile, setResubmitFile] = useState<File | null>(null);
  const [resubmitLevel, setResubmitLevel] = useState<JobDifficulty>('mid');
  const [resubmitTrack, setResubmitTrack] = useState<CandidateTrack>('BACKEND');
  const [confirmResubmitId, setConfirmResubmitId] = useState<number | null>(null);
  const [resubmitting, setResubmitting] = useState(false);

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

  // AI 初筛 / 评估进行中：静默轮询刷新进度（不打断页面，最多约 3 分钟）
  const hasPendingAi = items.some(item => item.stage === 'SCREENING' || item.stage === 'UNDER_REVIEW');
  useEffect(() => {
    if (!authed || !hasPendingAi) return;
    let attempts = 0;
    const timer = window.setInterval(() => {
      attempts += 1;
      if (attempts > POLL_MAX_ATTEMPTS) {
        window.clearInterval(timer);
        return;
      }
      candidateApi
        .listApplications()
        .then(data => setItems(data))
        .catch(() => {
          // 静默刷新失败时保留当前数据
        });
    }, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [authed, hasPendingAi]);

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

  const handleResubmitResume = async (id: number) => {
    if (!resubmitFile) {
      setError('请先选择修改后的简历文件');
      return;
    }
    setActingId(id);
    setError(null);
    // 先关闭确认框，改由等待浮层展示上传进度
    setConfirmResubmitId(null);
    setResubmitting(true);
    try {
      const updated = await candidateApi.resubmitResume(id, {
        file: resubmitFile,
        candidateLevel: resubmitLevel,
        candidateTrack: resubmitTrack,
      });
      setItems(prev => prev.map(item => (item.id === id ? updated : item)));
      setResubmitOpenId(null);
      setResubmitFile(null);
      showNotice('已重新提交简历，系统正在重新进行 AI 简历初筛');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setResubmitting(false);
      setActingId(null);
    }
  };

  return (
    <div className="space-y-4 sm:space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-xl font-bold text-slate-900 sm:text-2xl dark:text-white">我的投递</h1>
          <p className="mt-1 text-xs text-slate-500 sm:text-sm dark:text-slate-400">
            查看简历初筛进度、完成在线 AI 面试或确认线下面试安排
          </p>
        </div>
        <button
          type="button"
          onClick={loadList}
          className="btn-secondary flex items-center gap-1.5 rounded-xl px-3.5 py-2 text-sm sm:px-4"
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
            <div key={app.id} className="dark-card space-y-4 p-4 sm:p-6">
              <div className="flex flex-col gap-3 sm:flex-row sm:flex-wrap sm:items-start sm:justify-between">
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-2 sm:gap-2.5">
                    <h2 className="text-base font-bold text-slate-900 sm:text-lg dark:text-white">{app.jobTitle}</h2>
                    <span
                      className={`rounded-full px-3 py-0.5 text-xs font-medium ${
                        app.stage === 'REJECTED' && app.screeningFeedback
                          ? 'bg-amber-50 text-amber-800 dark:bg-amber-900/30 dark:text-amber-300'
                          : STAGE_BADGE[app.stage]
                      }`}
                    >
                      {app.stage === 'REJECTED' && app.screeningFeedback
                        ? '简历初筛未通过'
                        : CANDIDATE_STAGE_LABELS[app.stage]}
                    </span>
                    {app.candidateTrack && (
                      <span
                        data-testid={`my-app-track-${app.id}`}
                        className="rounded-full bg-cyan-50 px-2.5 py-0.5 text-xs font-medium text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300"
                      >
                        方向：{CANDIDATE_TRACK_LABELS[app.candidateTrack]}
                      </span>
                    )}
                    {app.candidateLevel && (
                      <span
                        data-testid={`my-app-level-${app.id}`}
                        className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-medium text-slate-700 dark:bg-slate-800 dark:text-slate-300"
                      >
                        定位：{JOB_DIFFICULTY_LABELS[app.candidateLevel]}
                      </span>
                    )}
                    {app.aiExperience && (
                      <span
                        data-testid={`my-app-ai-exp-${app.id}`}
                        className="rounded-full bg-indigo-50 px-2.5 py-0.5 text-xs font-medium text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300"
                      >
                        AI 经验：{AI_EXPERIENCE_LABELS[app.aiExperience]}
                      </span>
                    )}
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
                    className="btn-primary flex w-full items-center justify-center gap-1.5 rounded-xl px-5 py-2.5 text-sm sm:w-auto sm:py-2"
                  >
                    {app.stage === 'INTERVIEWING' ? '继续在线面试' : '开始在线面试'}
                    <ArrowRight className="h-4 w-4" />
                  </Link>
                )}
              </div>

              {/* 各阶段说明 */}
              {app.stage === 'SCREENING' && (
                <div className="space-y-3">
                  <p className="text-sm text-slate-600 dark:text-slate-300">
                    我们正在对您的简历进行智能筛选，筛选通过后将自动开启在线面试并发送邮件通知。
                  </p>
                  <AiWaitingPanel
                    variant="card"
                    title="AI 正在智能初筛您的简历"
                    description="结果出来后本页面会自动更新，您也可以先离开，稍后回来查看或留意邮件通知。"
                    estimate="通常 1–3 分钟"
                    tips={EVALUATION_TIPS}
                    testId={`screening-waiting-${app.id}`}
                  />
                </div>
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
                app.screeningFeedback ? (
                  <div
                    data-testid={`screening-feedback-${app.id}`}
                    className="space-y-3 rounded-xl border border-amber-200/80 bg-amber-50/50 p-4 text-sm dark:border-amber-800/60 dark:bg-amber-900/15"
                  >
                    <div className="flex flex-wrap items-center justify-between gap-2">
                      <div className="font-semibold text-amber-900 dark:text-amber-200">
                        简历初筛未通过原因
                      </div>
                      {app.screeningFeedback.canResubmit && (
                        <button
                          type="button"
                          data-testid={`open-resubmit-${app.id}`}
                          onClick={() => {
                            if (resubmitOpenId === app.id) {
                              setResubmitOpenId(null);
                              setResubmitFile(null);
                            } else {
                              setResubmitOpenId(app.id);
                              setResubmitFile(null);
                              setResubmitLevel(app.candidateLevel ?? 'mid');
                              setResubmitTrack(app.candidateTrack ?? inferDefaultCandidateTrack(app.jobTitle));
                            }
                          }}
                          className="btn-primary flex items-center gap-1.5 rounded-xl px-3.5 py-1.5 text-xs font-medium"
                        >
                          <Upload className="h-3.5 w-3.5" />
                          重新上传修改后的简历（剩余{' '}
                          {Math.max(1, app.screeningFeedback.maxRevisions - app.screeningFeedback.revisionCount)} 次机会）
                        </button>
                      )}
                    </div>
                    <p className="leading-relaxed text-slate-700 dark:text-slate-200">
                      {app.screeningFeedback.summary}
                    </p>
                    {app.screeningFeedback.missingRequirements.length > 0 && (
                      <div className="space-y-1.5 pt-0.5">
                        <div className="text-xs font-medium text-slate-600 dark:text-slate-300">
                          与岗位要求的主要差距：
                        </div>
                        <ul className="list-inside list-disc space-y-1 text-xs text-slate-600 dark:text-slate-300">
                          {app.screeningFeedback.missingRequirements.map((item, idx) => (
                            <li key={idx}>{item}</li>
                          ))}
                        </ul>
                      </div>
                    )}

                    {app.screeningFeedback.canResubmit ? (
                      <>
                        <p className="pt-0.5 text-xs text-slate-500 dark:text-slate-400">
                          每份投递提供 {app.screeningFeedback.maxRevisions} 次完善简历并重新初筛的机会。请结合本人真实经历补充项目职责与技术细节，切勿虚构或夸大；后续语音面试将围绕您简历中的内容逐项提问以核验简历真实性。
                        </p>

                        {resubmitOpenId === app.id && (
                          <div
                            data-testid={`resubmit-panel-${app.id}`}
                            className="space-y-3 rounded-xl border border-slate-200 bg-white p-4 dark:border-slate-700 dark:bg-slate-900/70"
                          >
                            <div
                              data-testid="resubmit-resume-warning"
                              className="flex items-start gap-2 rounded-xl border border-amber-300/80 bg-amber-50/80 p-3 text-xs leading-relaxed text-amber-950 dark:border-amber-700/60 dark:bg-amber-900/25 dark:text-amber-100"
                            >
                              <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
                              <div>
                                <strong>请勿乱提交简历：提交简历仅有两次机会</strong>（本次为最后一次修改重提机会）。
                                <strong>简历不允许造假，否则会直接不通过</strong>；进入面试答题时禁止搜题，后台会有提交审查。
                              </div>
                            </div>

                            <CandidateTrackSelector
                              value={resubmitTrack}
                              onChange={setResubmitTrack}
                              disabled={actingId === app.id}
                              label="确认或调整技术方向偏好"
                              hint="重新初筛及后续笔试、面试出题均按此方向进行"
                              testIdPrefix={`resubmit-track-${app.id}`}
                            />

                            <CandidateLevelSelector
                              value={resubmitLevel}
                              onChange={setResubmitLevel}
                              disabled={actingId === app.id}
                              label="确认或调整应聘定位"
                              hint="可按实际能力调整初级 / 中级 / 高级"
                              testIdPrefix={`resubmit-level-${app.id}`}
                            />

                            <div className="space-y-1.5">
                              <label className="block text-xs font-medium text-slate-700 dark:text-slate-300">
                                上传修改后的简历（支持 PDF / Word / TXT） *
                              </label>
                              <input
                                data-testid={`resubmit-file-${app.id}`}
                                type="file"
                                accept=".pdf,.docx,.doc,.txt"
                                onChange={e => setResubmitFile(e.target.files?.[0] ?? null)}
                                className="block w-full text-xs text-slate-600 file:mr-3 file:rounded-lg file:border-0 file:bg-primary-50 file:px-3 file:py-1.5 file:text-xs file:font-semibold file:text-primary-700 hover:file:bg-primary-100 dark:text-slate-300 dark:file:bg-primary-900/30 dark:file:text-primary-300"
                              />
                            </div>

                            <div className="flex justify-end gap-2 pt-1">
                              <button
                                type="button"
                                onClick={() => {
                                  setResubmitOpenId(null);
                                  setResubmitFile(null);
                                }}
                                className="btn-secondary rounded-xl px-4 py-1.5 text-xs"
                              >
                                取消
                              </button>
                              <button
                                type="button"
                                data-testid={`confirm-resubmit-${app.id}`}
                                disabled={!resubmitFile || actingId === app.id}
                                onClick={() => setConfirmResubmitId(app.id)}
                                className="btn-primary flex items-center gap-1.5 rounded-xl px-4 py-1.5 text-xs font-medium disabled:opacity-50"
                              >
                                {actingId === app.id && <Loader2 className="h-3.5 w-3.5 animate-spin" />}
                                确认重新提交初筛
                              </button>
                            </div>
                          </div>
                        )}
                      </>
                    ) : (
                      <p className="pt-0.5 text-xs text-slate-500 dark:text-slate-400">
                        {app.screeningFeedback.revisionCount > 0
                          ? `已使用完 ${app.screeningFeedback.maxRevisions} 次简历修改重提机会，本次未能进入下一环节，欢迎继续关注其他匹配职位。`
                          : '建议在简历中补充上述相关技术或项目实践细节，欢迎继续关注其他匹配职位。'}
                      </p>
                    )}
                  </div>
                ) : app.assessmentFeedback ? (
                  <CandidateAssessmentFeedbackCard
                    applicationId={app.id}
                    feedback={app.assessmentFeedback}
                  />
                ) : (
                  <p className="text-sm text-slate-500 dark:text-slate-400">
                    感谢您的关注与投入，经综合评估本次未能进入下一环节，祝您求职顺利。
                  </p>
                )
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
                    <div className="flex flex-col gap-2 pt-2 sm:flex-row">
                      <input
                        value={rescheduleNote}
                        onChange={e => setRescheduleNote(e.target.value)}
                        maxLength={500}
                        placeholder="请填写改期原因及您方便参加的日期时间段"
                        className="w-full min-w-0 flex-1 rounded-xl border border-slate-200 bg-white px-3 py-2 text-xs dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                      />
                      <button
                        type="button"
                        disabled={actingId === app.id}
                        onClick={() => handleReschedule(app.id)}
                        className="btn-primary rounded-xl px-4 py-2 text-xs sm:py-1.5"
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

      <ConfirmDialog
        open={confirmResubmitId !== null}
        title="最后一次提交简历确认（第 2 / 2 次机会）"
        message={
          '请再次确认：\n1. 提交简历仅有两次机会，本次为最后一次重提机会，请勿乱提交简历；\n2. 简历不允许造假，一经发现虚构经历或造假将直接不通过；\n3. 后续答题时禁止搜题，后台会有提交审查。\n\n确认当前修改后的简历真实无误并立即提交初筛吗？'
        }
        confirmText="确认真实无误并提交"
        confirmVariant="warning"
        confirmTestId="confirm-resubmit-dialog-btn"
        loading={confirmResubmitId !== null && actingId === confirmResubmitId}
        onConfirm={() => {
          if (confirmResubmitId !== null) {
            void handleResubmitResume(confirmResubmitId);
          }
        }}
        onCancel={() => setConfirmResubmitId(null)}
      />

      <AiWaitingOverlay
        open={resubmitting}
        title="正在上传修改后的简历"
        description="上传完成后系统将自动重新进行 AI 简历初筛，结果会实时更新在本页面。"
        estimate="通常 5–20 秒"
        tips={GENERAL_WAITING_TIPS}
        testId="resubmit-uploading-overlay"
      />
    </div>
  );
}
