import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { ArrowLeft, CheckCircle2, Clock, Info, Loader2, MapPin, Send, Upload, Users } from 'lucide-react';
import { candidateApi } from '../../api/candidate';
import { publicJobApi } from '../../api/job';
import { getErrorMessage } from '../../api/request';
import { ROUTES } from '../../constants/routes';
import { formatDateTime } from '../../utils/date';
import {
  CANDIDATE_AUTH_CHANGED_EVENT,
  getStoredCandidate,
  updateStoredCandidate,
} from '../../utils/candidateStorage';
import { EMPLOYMENT_TYPE_LABELS, INTERVIEW_MODE_LABELS, type PublicJobDetail } from '../../types/job';
import type { CandidateProfile } from '../../types/recruitment';

export default function CareerJobDetailPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const navigate = useNavigate();

  const [job, setJob] = useState<PublicJobDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [candidate, setCandidate] = useState<CandidateProfile | null>(getStoredCandidate);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [consent, setConsent] = useState(false);
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
      .then(setJob)
      .catch(err => setError(getErrorMessage(err)))
      .finally(() => setLoading(false));
  }, [jobId]);

  const handleApply = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!job) return;
    if (!name.trim()) {
      setApplyError('请填写真实姓名');
      return;
    }
    if (!file) {
      setApplyError('请上传简历文件（支持 PDF / Word / Markdown / TXT）');
      return;
    }
    if (!consent) {
      setApplyError('请阅读并勾选个人信息与在线面试授权声明');
      return;
    }
    setSubmitting(true);
    setApplyError(null);
    try {
      const app = await candidateApi.apply({
        jobId: job.id,
        file,
        name: name.trim(),
        phone: phone.trim() || undefined,
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
    <div className="space-y-6">
      <Link
        to={ROUTES.careers}
        className="inline-flex items-center gap-1 text-sm text-slate-500 hover:text-primary-600 dark:text-slate-400"
      >
        <ArrowLeft className="h-4 w-4" />
        全部职位
      </Link>

      <div className="dark-card space-y-3 p-6">
        <div className="flex flex-wrap items-center gap-3">
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">{job.title}</h1>
          {job.salaryRange && (
            <span className="text-lg font-semibold text-primary-600 dark:text-primary-400">{job.salaryRange}</span>
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

      <div className="dark-card p-6">
        <h2 className="mb-3 text-base font-semibold text-slate-900 dark:text-white">职位描述</h2>
        <div className="prose prose-slate max-w-none text-sm dark:prose-invert">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{job.jdText}</ReactMarkdown>
        </div>
      </div>

      <div className="dark-card space-y-3 p-6">
        <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-white">
          <Info className="h-5 w-5 text-primary-500" />
          应聘流程说明
        </h2>
        <ol className="list-decimal space-y-1 pl-5 text-sm text-slate-600 dark:text-slate-300">
          <li>验证邮箱并上传个人简历</li>
          <li>
            简历初筛通过后进入在线 AI 面试（{INTERVIEW_MODE_LABELS[job.interviewMode]}，约 {job.questionCount} 题
            {job.timeLimitMinutes ? `，限时 ${job.timeLimitMinutes} 分钟` : ''}）
          </li>
          <li>AI 自动评估与 HR 复核</li>
          <li>审核通过后通过邮件邀请您参加线下面试</li>
        </ol>
      </div>

      {/* 投递区域 */}
      <div id="apply" className="dark-card space-y-4 p-6">
        <h2 className="text-base font-semibold text-slate-900 dark:text-white">投递该职位</h2>

        {!candidate ? (
          <div className="flex flex-wrap items-center justify-between gap-4 rounded-xl bg-slate-50 p-4 dark:bg-slate-800/60">
            <p className="text-sm text-slate-600 dark:text-slate-300">
              投递前请先完成邮箱验证，以便接收在线面试与线下面试邀约通知
            </p>
            <Link to={loginRedirect} className="btn-primary flex items-center gap-2 rounded-xl px-6 py-2.5 text-sm">
              <Send className="h-4 w-4" />
              验证邮箱并投递
            </Link>
          </div>
        ) : (
          <form onSubmit={handleApply} className="space-y-4">
            {applyError && (
              <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
                {applyError}
              </div>
            )}

            <div className="flex items-center gap-2 text-xs text-emerald-700 dark:text-emerald-400">
              <CheckCircle2 className="h-4 w-4" />
              当前验证邮箱：{candidate.email}
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

            <label className="flex items-start gap-2 text-xs text-slate-600 dark:text-slate-300">
              <input
                type="checkbox"
                checked={consent}
                onChange={e => setConsent(e.target.checked)}
                className="mt-0.5 h-4 w-4 accent-primary-500"
              />
              <span>
                我已阅读并同意将本人提交的简历信息及后续在线 AI 面试记录用于本次岗位招聘评估。
              </span>
            </label>

            <div className="flex justify-end">
              <button
                type="submit"
                disabled={submitting}
                className="btn-primary flex items-center gap-2 rounded-xl px-8 py-2.5 text-sm font-medium disabled:opacity-60"
              >
                {submitting ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                {submitting ? '正在上传并提交…' : '提交简历'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>
  );
}
