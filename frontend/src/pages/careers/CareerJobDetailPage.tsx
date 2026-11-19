import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ArrowLeft, AlertTriangle, CheckCircle2, Clock, Info, Loader2, MapPin, Send, ShieldAlert, Upload, Users } from 'lucide-react';
import { candidateApi } from '../../api/candidate';
import { publicJobApi } from '../../api/job';
import { getErrorMessage } from '../../api/request';
import ConfirmDialog from '../../components/ConfirmDialog';
import AiWaitingOverlay from '../../components/waiting/AiWaitingOverlay';
import { GENERAL_WAITING_TIPS } from '../../components/waiting/waitingTips';
import { ROUTES } from '../../constants/routes';
import { formatDateTime } from '../../utils/date';
import {
  CANDIDATE_AUTH_CHANGED_EVENT,
  getStoredCandidate,
  updateStoredCandidate,
} from '../../utils/candidateStorage';
import {
  EMPLOYMENT_TYPE_LABELS,
  INTERVIEW_MODE_LABELS,
  JOB_DIFFICULTY_LABELS,
  type JobDifficulty,
  type PublicJobDetail,
} from '../../types/job';
import {
   inferDefaultCandidateTrack,
  type CandidateJobInvitationStatus,
  type CandidateProfile,
  type CandidateTrack,
} from '../../types/recruitment';
import CandidateLevelSelector from '../../components/CandidateLevelSelector';
import CandidateTrackSelector from '../../components/CandidateTrackSelector';

export default function CareerJobDetailPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const navigate = useNavigate();

  const [job, setJob] = useState<PublicJobDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [candidate, setCandidate] = useState<CandidateProfile | null>(getStoredCandidate);
  const [invStatus, setInvStatus] = useState<CandidateJobInvitationStatus | null>(null);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [candidateLevel, setCandidateLevel] = useState<JobDifficulty>('mid');
  const [candidateTrack, setCandidateTrack] = useState<CandidateTrack>('BACKEND');
  const [file, setFile] = useState<File | null>(null);
  const [consent, setConsent] = useState(false);
  const [confirmApplyOpen, setConfirmApplyOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [applyError, setApplyError] = useState<string | null>(null);

  useEffect(() => {
    const sync = () => {
      const stored = getStoredCandidate();
      setCandidate(stored);
      if (stored) {
        setName(prev => prev || stored.name || '');
        setPhone(prev => prev || stored.phone || '');
      }
    };
    sync();
    window.addEventListener(CANDIDATE_AUTH_CHANGED_EVENT, sync);
    return () => window.removeEventListener(CANDIDATE_AUTH_CHANGED_EVENT, sync);
  }, []);

  useEffect(() => {
    const id = Number(jobId);
    if (!jobId || Number.isNaN(id)) {
      setError('职位不存在');
      setLoading(false);
      return;
    }
    publicJobApi
      .get(id)
      .then(data => {
        setJob(data);
        if (data.difficulty) {
          setCandidateLevel(data.difficulty);
        }
        setCandidateTrack(inferDefaultCandidateTrack(data.title));
      })
      .catch(err => setError(getErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [jobId]);

  useEffect(() => {
    if (!candidate || !job) {
      setInvStatus(null);
      return;
    }
    let cancelled = false;
    candidateApi
      .getJobInvitation(job.id)
      .then(res => {
        if (!cancelled) {
          setInvStatus(res);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setInvStatus(null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [candidate, job]);

  const handleApply = (e: React.FormEvent) => {
    e.preventDefault();
    if (!job) return;
    if (invStatus && !invStatus.canApply) {
      setApplyError(invStatus.message || '非受邀邮箱不允许投递简历与参加面试');
      return;
    }
    if (!name.trim()) {
      setApplyError('请填写真实姓名');
      return;
    }
    if (!file) {
      setApplyError('请上传简历文件（支持 PDF / Word / Markdown / TXT）');
      return;
    }
    if (!consent) {
      setApplyError('请阅读提交前提醒并勾选授权与诚信承诺声明');
      return;
    }
    setApplyError(null);
    setConfirmApplyOpen(true);
  };

  const executeApply = async () => {
    if (!job || !file) return;
    // 先关闭确认框，改由等待浮层展示上传解析进度
    setConfirmApplyOpen(false);
    setSubmitting(true);
    setApplyError(null);
    try {
      const app = await candidateApi.apply({
        jobId: job.id,
        file,
        name: name.trim(),
        phone: phone.trim() || undefined,
        candidateLevel,
        candidateTrack,
        consent,
      });
      if (candidate) {
        updateStoredCandidate({
          ...candidate,
          name: name.trim(),
          phone: phone.trim() || candidate.phone,
        });
      }
      if (app.stage === 'INTERVIEW_PENDING') {
        navigate(ROUTES.careerInterview(app.id));
      } else {
        navigate(ROUTES.careerMe);
      }
    } catch (err) {
      setApplyError(getErrorMessage(err));
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  if (error || !job) {
    return (
      <div className="dark-card flex flex-col items-center gap-4 py-16 text-center">
        <p className="text-slate-600 dark:text-slate-300">{error || '职位不存在或已停止招聘'}</p>
        <Link to={ROUTES.careers} className="btn-primary rounded-xl px-4 py-2 text-sm">
          查看其他职位
        </Link>
      </div>
    );
  }

  const loginRedirect = `${ROUTES.careerLogin}?redirect=${encodeURIComponent(ROUTES.careerJob(job.id))}`;

  return (
    <div className="space-y-4 sm:space-y-6">
      <Link
        to={ROUTES.careers}
        className="inline-flex items-center gap-1 text-sm text-slate-500 hover:text-primary-600 dark:text-slate-400"
      >
        <ArrowLeft className="h-4 w-4" />
        全部职位
      </Link>

      <div className="dark-card space-y-3 p-4 sm:p-6">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-xl font-bold text-slate-900 sm:text-2xl dark:text-white">{job.title}</h1>
          {job.salaryRange && (
            <span className="text-base font-semibold text-primary-600 sm:text-lg dark:text-primary-400">{job.salaryRange}</span>
          )}
          {job.difficulty && (
            <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs font-medium text-slate-600 dark:bg-slate-800 dark:text-slate-300">
              建议定位：{JOB_DIFFICULTY_LABELS[job.difficulty]}
            </span>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-slate-500 dark:text-slate-400">
          {job.department && <span>{job.department}</span>}
          {job.location && (
            <span className="flex items-center gap-1">
              <MapPin className="h-3.5 w-3.5" />
              {job.location}
            </span>
          )}
          <span>{EMPLOYMENT_TYPE_LABELS[job.employmentType]}</span>
          {job.headcount && (
            <span className="flex items-center gap-1">
              <Users className="h-3.5 w-3.5" />招 {job.headcount} 人
            </span>
          )}
          {job.deadline && (
            <span className="flex items-center gap-1">
              <Clock className="h-3.5 w-3.5" />
              截止 {formatDateTime(job.deadline)}
            </span>
          )}
        </div>
      </div>

      <div className="dark-card p-4 sm:p-6">
        <h2 className="mb-3 text-base font-semibold text-slate-900 dark:text-white">职位描述</h2>
        <div className="prose prose-slate max-w-none text-sm dark:prose-invert">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{job.jdText}</ReactMarkdown>
        </div>
      </div>

      <div className="dark-card space-y-3 p-4 sm:p-6">
        <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-white">
          <Info className="h-5 w-5 text-primary-500" />
          应聘流程说明
        </h2>
        <ol className="list-decimal space-y-1 pl-5 text-sm text-slate-600 dark:text-slate-300">
          <li>通过受邀邮箱完成验证（非受邀邮箱不允许投递简历与进入面试），选择技术方向偏好（偏向前端 / 偏向后端）与应聘定位并上传真实简历</li>
          <li>
            后续简历初筛与在线 AI 出题（{INTERVIEW_MODE_LABELS[job.interviewMode]}，约 {job.questionCount} 题
            {job.timeLimitMinutes ? `，限时 ${job.timeLimitMinutes} 分钟` : ''}）全程按您投递时所选方向进行，答题时无需额外再选方向（答题时禁止搜题，后台会有提交审查）
          </li>
          <li>AI 自动评估与 HR 复核（若简历存在造假将直接不通过）</li>
          <li>审核通过后通过邮件邀请您参加线下面试</li>
        </ol>
      </div>

      {/* 投递区域 */}
      <div id="apply" className="dark-card space-y-4 p-4 sm:p-6">
        <h2 className="text-base font-semibold text-slate-900 dark:text-white">投递该职位</h2>

        {!candidate ? (
          <div className="flex flex-wrap items-center justify-between gap-4 rounded-xl bg-slate-50 p-3.5 sm:p-4 dark:bg-slate-800/60">
            <div className="space-y-1">
              <p className="text-sm text-slate-700 dark:text-slate-200">
                本岗位实行受邀面试制：请先使用收到面试邀请邮件的邮箱完成验证后投递简历
              </p>
              <p className="text-xs text-amber-700 dark:text-amber-300">
                提示：非邀请邮箱不允许投递简历面试；提交简历仅有两次机会且不允许造假，否则会直接不通过。
              </p>
            </div>
            <Link
              to={loginRedirect}
              className="btn-primary flex w-full items-center justify-center gap-2 rounded-xl px-6 py-3 text-sm sm:w-auto sm:py-2.5"
            >
              <Send className="h-4 w-4" />
              验证受邀邮箱并投递
            </Link>
          </div>
        ) : invStatus && !invStatus.canApply ? (
          <div
            data-testid="not-invited-banner"
            className="space-y-2 rounded-xl border border-rose-200 bg-rose-50/70 p-5 text-sm text-rose-800 dark:border-rose-800/60 dark:bg-rose-900/20 dark:text-rose-200"
          >
            <div className="flex items-center gap-2 font-semibold">
              <ShieldAlert className="h-5 w-5 shrink-0 text-rose-600 dark:text-rose-400" />
              非受邀邮箱不允许投递简历与参加面试
            </div>
            <p className="leading-relaxed">
              {invStatus.message || '当前邮箱未收到本岗位的面试邀请，面试只允许受邀邮箱进入，非邀请的邮箱不允许投递简历面试。'}
            </p>
            <p className="text-xs opacity-80">
              当前验证邮箱：{candidate.email}。如您的其他邮箱收到了 HR 面试邀请，请退出后切换受邀邮箱登录。
            </p>
          </div>
        ) : (
          <form onSubmit={handleApply} className="space-y-4">
            {applyError && (
              <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
                {applyError}
              </div>
            )}

            <div className="flex flex-wrap items-center gap-2 text-xs text-emerald-700 dark:text-emerald-400">
              <CheckCircle2 className="h-4 w-4" />
              <span>当前验证邮箱：{candidate.email}</span>
              {invStatus?.invited && (
                <span className="rounded-full bg-emerald-100 px-2.5 py-0.5 font-medium text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200">
                  已获本岗位面试邀请{invStatus.skipScreening ? ' · 免初筛直通面试' : ''}
                </span>
              )}
            </div>

            <div className="grid gap-4 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300">姓名 *</label>
                <input
                  required
                  maxLength={64}
                  value={name}
                  onChange={e => setName(e.target.value)}
                  placeholder="请输入真实姓名"
                  className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                />
              </div>
              <div>
                <label className="mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300">手机号（选填）</label>
                <input
                  maxLength={32}
                  value={phone}
                  onChange={e => setPhone(e.target.value)}
                  placeholder="便于 HR 联系线下面试"
                  className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                />
              </div>
            </div>

            <CandidateTrackSelector
              value={candidateTrack}
              onChange={setCandidateTrack}
              disabled={submitting}
              label="选择技术方向偏好 *"
              hint="后续简历初筛、在线笔试与面试出题均按所选方向进行，答题时无需再选"
              testIdPrefix="apply-track"
            />

            <CandidateLevelSelector
              value={candidateLevel}
              onChange={setCandidateLevel}
              recommendedLevel={job.difficulty ?? null}
              disabled={submitting}
              label="选择您的应聘定位 *"
              hint="AI 笔试与面试将按所选定位出题（开考前仍可调整）"
              testIdPrefix="apply-level"
            />

            <div>
              <label className="mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300">
                上传简历 *（PDF / Word / Markdown / TXT）
              </label>
              <label className="flex cursor-pointer items-center justify-center gap-2 rounded-xl border-2 border-dashed border-slate-200 bg-slate-50/60 px-4 py-5 text-sm text-slate-600 transition hover:border-primary-400 dark:border-slate-700 dark:bg-slate-800/40 dark:text-slate-300">
                <Upload className="h-4 w-4 text-primary-500" />
                <span>{file ? `已选择：${file.name}` : '点击选择简历文件'}</span>
                <input
                  type="file"
                  accept=".pdf,.doc,.docx,.md,.txt"
                  className="hidden"
                  onChange={e => setFile(e.target.files?.[0] ?? null)}
                />
              </label>
            </div>

            {/* 提交前醒目诚信提醒 */}
            <div
              data-testid="resume-submit-warning"
              className="space-y-1.5 rounded-xl border border-amber-300/80 bg-amber-50/80 p-4 text-xs leading-relaxed text-amber-950 dark:border-amber-700/60 dark:bg-amber-900/25 dark:text-amber-100"
            >
              <div className="flex items-center gap-1.5 font-semibold text-amber-900 dark:text-amber-200">
                <AlertTriangle className="h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
                提交简历前重要提醒（请勿乱提交简历）
              </div>
              <ul className="list-disc space-y-1 pl-5">
                <li>
                  <strong>请勿乱提交简历，提交简历仅有两次机会</strong>（首次提交 + 最多 1 次初筛未通过后的修改重提机会），次数用尽后无法再次提交。
                </li>
                <li>
                  <strong>简历不允许造假，否则会直接不通过</strong>：请确保教育背景、工作履历与项目成果真实准确，后续在线面试将围绕您简历中的细节逐项追问核验。
                </li>
                <li>
                  进入在线笔试或面试<strong>答题时禁止搜题</strong>、切屏查资料，<strong>后台会有提交审查</strong>。
                </li>
              </ul>
            </div>

            <label className="flex items-start gap-2 text-xs text-slate-600 dark:text-slate-300">
              <input
                type="checkbox"
                checked={consent}
                onChange={e => setConsent(e.target.checked)}
                className="mt-0.5 h-4 w-4 accent-primary-500"
              />
              <span>
                我已阅读上述提醒，知晓提交简历仅有两次机会且简历不允许造假（否则直接不通过），并同意将简历信息及在线 AI 面试记录用于本次岗位招聘评估。
              </span>
            </label>

            <div className="flex justify-end">
              <button
                type="submit"
                disabled={submitting}
                className="btn-primary flex w-full items-center justify-center gap-2 rounded-xl px-8 py-3 text-sm font-medium disabled:opacity-60 sm:w-auto sm:py-2.5"
              >
                {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                {submitting ? '正在上传并提交…' : '提交简历'}
              </button>
            </div>
          </form>
        )}
      </div>

      <ConfirmDialog
        open={confirmApplyOpen}
        title="提交简历前诚信确认（共 2 次机会）"
        message={
          '请再次确认以下重要事项：\n1. 请勿乱提交简历，本岗位提交简历仅有两次机会（首次提交 + 最多 1 次修改重提）；\n2. 简历不允许造假，若发现虚构经历或造假将直接不通过；\n3. 后续答题时禁止搜题，后台会有提交审查。\n\n确认当前上传的简历真实无误并立即提交吗？'
        }
        confirmText="确认真实无误并提交"
        confirmVariant="warning"
        confirmTestId="confirm-apply-dialog-btn"
        loading={submitting}
        onConfirm={() => void executeApply()}
        onCancel={() => setConfirmApplyOpen(false)}
      />

      <AiWaitingOverlay
        open={submitting}
        title="正在上传并解析您的简历"
        description="系统正在安全上传简历并提取关键信息，完成后将自动跳转，请勿关闭或刷新页面。"
        estimate="通常 5–20 秒"
        tips={GENERAL_WAITING_TIPS}
        testId="apply-uploading-overlay"
      />
    </div>
  );
}
