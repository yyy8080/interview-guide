import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import {
  Briefcase,
  CheckCircle2,
  Copy,
  Edit3,
  ExternalLink,
  Link2,
  Loader2,
  MapPin,
  Plus,
  Power,
  RefreshCw,
  Search,
  Trash2,
  Users,
} from 'lucide-react';
import { jobApi } from '../../api/job';
import { getErrorMessage } from '../../api/request';
import ConfirmDialog from '../../components/ConfirmDialog';
import { ROUTES } from '../../constants/routes';
import { getStoredUser } from '../../utils/authStorage';
import { formatDateTime } from '../../utils/date';
import {
  EMPLOYMENT_TYPE_LABELS,
  INTERVIEW_MODE_LABELS,
  JOB_STATUS_LABELS,
  type JobPosition,
  type JobStatus,
} from '../../types/job';

type StatusFilter = JobStatus | 'ALL';

const STATUS_TABS: { value: StatusFilter; label: string }[] = [
  { value: 'ALL', label: '全部' },
  { value: 'OPEN', label: '招聘中' },
  { value: 'DRAFT', label: '草稿' },
  { value: 'CLOSED', label: '已关闭' },
];

const STATUS_BADGE: Record<JobStatus, string> = {
  DRAFT: 'bg-slate-100 text-slate-600 dark:bg-slate-700 dark:text-slate-300',
  OPEN: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300',
  CLOSED: 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
};

interface PendingAction {
  job: JobPosition;
  type: 'publish' | 'close' | 'reopen' | 'delete';
}

const ACTION_TEXT: Record<PendingAction['type'], { title: string; confirm: string; message: string }> = {
  publish: { title: '发布岗位', confirm: '发布', message: '发布后候选人即可在招聘门户看到该岗位并投递简历。' },
  close: { title: '关闭岗位', confirm: '关闭', message: '关闭后候选人将无法继续投递，已有投递不受影响。' },
  reopen: { title: '重新开放岗位', confirm: '开放', message: '重新开放后候选人可继续投递该岗位。' },
  delete: { title: '删除草稿岗位', confirm: '删除', message: '删除后无法恢复，确定要删除该草稿吗？' },
};

export default function JobListPage() {
  const navigate = useNavigate();
  const currentUser = getStoredUser();
  const canWrite = currentUser?.role === 'ADMIN' || currentUser?.role === 'HR';
  const [jobs, setJobs] = useState<JobPosition[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('ALL');
  const [keyword, setKeyword] = useState('');
  const [appliedKeyword, setAppliedKeyword] = useState('');
  const [pending, setPending] = useState<PendingAction | null>(null);
  const [acting, setActing] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const showNotice = useCallback((message: string) => {
    setNotice(message);
    window.setTimeout(() => setNotice(null), 2500);
  }, []);

  const loadJobs = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const data = await jobApi.list({
        status: statusFilter === 'ALL' ? undefined : statusFilter,
        keyword: appliedKeyword || undefined,
      });
      setJobs(data);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [statusFilter, appliedKeyword]);

  useEffect(() => {
    loadJobs();
  }, [loadJobs]);

  const stats = useMemo(() => ({
    total: jobs.length,
    open: jobs.filter(j => j.status === 'OPEN').length,
  }), [jobs]);

  const handleConfirm = async () => {
    if (!pending) return;
    setActing(true);
    try {
      const { job, type } = pending;
      if (type === 'delete') {
        await jobApi.delete(job.id);
        showNotice('草稿已删除');
      } else {
        const target: JobStatus = type === 'close' ? 'CLOSED' : 'OPEN';
        await jobApi.changeStatus(job.id, target);
        showNotice(type === 'close' ? '岗位已关闭' : '岗位已发布');
      }
      setPending(null);
      await loadJobs();
    } catch (err) {
      setError(getErrorMessage(err));
      setPending(null);
    } finally {
      setActing(false);
    }
  };

  const handleCopy = async (job: JobPosition) => {
    try {
      const copy = await jobApi.copy(job.id);
      navigate(ROUTES.adminJobEdit(copy.id));
    } catch (err) {
      setError(getErrorMessage(err));
    }
  };

  const handleCopyLink = async (job: JobPosition) => {
    const url = `${window.location.origin}${ROUTES.careerJob(job.id)}`;
    try {
      await navigator.clipboard.writeText(url);
      showNotice('投递链接已复制');
    } catch {
      window.prompt('复制投递链接', url);
    }
  };

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">岗位管理</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            配置招聘岗位、在线 AI 面试参数与审核分数线 · 共 {stats.total} 个岗位，{stats.open} 个招聘中
          </p>
        </div>
        <div className="flex gap-2">
          <a
            href={ROUTES.careers}
            target="_blank"
            rel="noreferrer"
            className="btn-secondary flex items-center gap-2 rounded-xl px-4 py-2 text-sm"
          >
            <ExternalLink className="h-4 w-4" />
            招聘门户
          </a>
          {canWrite && (
            <Link to={ROUTES.adminJobCreate} className="btn-primary flex items-center gap-2 rounded-xl px-4 py-2 text-sm">
              <Plus className="h-4 w-4" />
              新建岗位
            </Link>
          )}
        </div>
      </div>

      <div className="dark-card flex flex-wrap items-center justify-between gap-3 p-3">
        <div className="flex gap-1">
          {STATUS_TABS.map(tab => (
            <button
              key={tab.value}
              onClick={() => setStatusFilter(tab.value)}
              className={`rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
                statusFilter === tab.value
                  ? 'bg-primary-50 text-primary-700 dark:bg-primary-900/40 dark:text-primary-300'
                  : 'text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-700'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
        <form
          className="flex items-center gap-2"
          onSubmit={(e) => {
            e.preventDefault();
            setAppliedKeyword(keyword.trim());
          }}
        >
          <div className="relative">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
            <input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="搜索岗位名称 / 部门"
              className="dark-input w-60 rounded-lg py-1.5 pl-9 pr-3 text-sm outline-none"
            />
          </div>
          <button type="button" onClick={loadJobs} className="btn-secondary rounded-lg p-2" title="刷新">
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
          </button>
        </form>
      </div>

      {notice && (
        <div className="flex items-center gap-2 rounded-xl bg-emerald-50 px-4 py-2 text-sm text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300">
          <CheckCircle2 className="h-4 w-4" />
          {notice}
        </div>
      )}
      {error && (
        <div className="rounded-xl bg-red-50 px-4 py-2 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">{error}</div>
      )}

      {loading && jobs.length === 0 ? (
        <div className="flex justify-center py-20">
          <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
        </div>
      ) : jobs.length === 0 ? (
        <div className="dark-card flex flex-col items-center gap-3 py-16 text-center">
          <Briefcase className="h-10 w-10 text-slate-300 dark:text-slate-600" />
          <p className="text-slate-500 dark:text-slate-400">暂无岗位</p>
          {canWrite && (
            <Link to={ROUTES.adminJobCreate} className="btn-primary rounded-xl px-4 py-2 text-sm">创建第一个岗位</Link>
          )}
        </div>
      ) : (
        <div className="space-y-3">
          {jobs.map(job => (
            <div key={job.id} className="dark-card flex flex-wrap items-center gap-4 p-5">
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  {canWrite ? (
                    <Link
                      to={ROUTES.adminJobEdit(job.id)}
                      className="truncate text-lg font-semibold text-slate-900 hover:text-primary-600 dark:text-white dark:hover:text-primary-400"
                    >
                      {job.title}
                    </Link>
                  ) : (
                    <span className="truncate text-lg font-semibold text-slate-900 dark:text-white">{job.title}</span>
                  )}
                  <span className={`rounded-full px-2.5 py-0.5 text-xs font-semibold ${STATUS_BADGE[job.status]}`}>
                    {JOB_STATUS_LABELS[job.status]}
                  </span>
                  <span className="rounded-full bg-primary-50 px-2.5 py-0.5 text-xs font-medium text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
                    {INTERVIEW_MODE_LABELS[job.interviewMode]}
                  </span>
                </div>
                <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-slate-500 dark:text-slate-400">
                  {job.department && <span>{job.department}</span>}
                  {job.location && (
                    <span className="flex items-center gap-1"><MapPin className="h-3.5 w-3.5" />{job.location}</span>
                  )}
                  <span>{EMPLOYMENT_TYPE_LABELS[job.employmentType]}</span>
                  {job.headcount && (
                    <span className="flex items-center gap-1"><Users className="h-3.5 w-3.5" />{job.headcount} 人</span>
                  )}
                  <span>{job.questionCount} 题 · 通过线 {job.aiPassThreshold} 分</span>
                  {job.screeningEnabled && <span>简历初筛 ≥ {job.screeningThreshold} 分</span>}
                </div>
                <div className="mt-1 text-xs text-slate-400 dark:text-slate-500">
                  更新于 {formatDateTime(job.updatedAt)}
                  {job.deadline && ` · 截止 ${formatDateTime(job.deadline)}`}
                </div>
              </div>

              <div className="flex flex-wrap items-center gap-2">
                <Link
                  to={`${ROUTES.adminApplications}?jobId=${job.id}`}
                  className="btn-secondary flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm"
                >
                  <Users className="h-4 w-4" />查看投递
                </Link>
                {job.status === 'OPEN' && (
                  <button onClick={() => handleCopyLink(job)} className="btn-secondary flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm">
                    <Link2 className="h-4 w-4" />投递链接
                  </button>
                )}
                {canWrite && (
                  <>
                    <Link to={ROUTES.adminJobEdit(job.id)} className="btn-secondary flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm">
                      <Edit3 className="h-4 w-4" />编辑
                    </Link>
                    <button onClick={() => handleCopy(job)} className="btn-secondary flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm">
                      <Copy className="h-4 w-4" />复制
                    </button>
                    {job.status === 'DRAFT' && (
                      <>
                        <button
                          onClick={() => setPending({ job, type: 'publish' })}
                          className="btn-primary flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm"
                        >
                          <Power className="h-4 w-4" />发布
                        </button>
                        <button
                          onClick={() => setPending({ job, type: 'delete' })}
                          className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm text-red-600 hover:bg-red-50 dark:text-red-400 dark:hover:bg-red-900/20"
                        >
                          <Trash2 className="h-4 w-4" />删除
                        </button>
                      </>
                    )}
                    {job.status === 'OPEN' && (
                      <button
                        onClick={() => setPending({ job, type: 'close' })}
                        className="flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm text-amber-700 hover:bg-amber-50 dark:text-amber-300 dark:hover:bg-amber-900/20"
                      >
                        <Power className="h-4 w-4" />关闭
                      </button>
                    )}
                    {job.status === 'CLOSED' && (
                      <button
                        onClick={() => setPending({ job, type: 'reopen' })}
                        className="btn-primary flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm"
                      >
                        <Power className="h-4 w-4" />重新开放
                      </button>
                    )}
                  </>
                )}
              </div>
            </div>
          ))}
        </div>
      )}

      <ConfirmDialog
        open={pending !== null}
        title={pending ? ACTION_TEXT[pending.type].title : ''}
        message={pending ? `「${pending.job.title}」${ACTION_TEXT[pending.type].message}` : ''}
        confirmText={pending ? ACTION_TEXT[pending.type].confirm : '确定'}
        confirmVariant={pending?.type === 'delete' ? 'danger' : pending?.type === 'close' ? 'warning' : 'primary'}
        loading={acting}
        onConfirm={handleConfirm}
        onCancel={() => setPending(null)}
      />
    </div>
  );
}
