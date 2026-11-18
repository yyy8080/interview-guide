import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  Loader2,
  RefreshCw,
  Search,
  Send,
  XCircle,
} from 'lucide-react';
import { applicationApi } from '../../api/application';
import { jobApi } from '../../api/job';
import { getErrorMessage } from '../../api/request';
import { ROUTES } from '../../constants/routes';
import { getStoredUser } from '../../utils/authStorage';
import { formatDateTime } from '../../utils/date';
import { INTERVIEW_MODE_LABELS, JOB_DIFFICULTY_LABELS, type JobPosition } from '../../types/job';
import {
  APPLICATION_STATUS_BADGES,
  APPLICATION_STATUS_LABELS,
  PROCTOR_RISK_BADGES,
  PROCTOR_RISK_LABELS,
  type ApplicationListItem,
  type ApplicationStatus,
  type ProctorRiskLevel,
} from '../../types/recruitment';

type StatusGroupKey = 'ALL' | 'PENDING_HR' | 'IN_PROGRESS' | 'PASSED' | 'REJECTED' | 'EXPIRED';

const STATUS_GROUPS: { key: StatusGroupKey; label: string; statuses?: ApplicationStatus[] }[] = [
  { key: 'ALL', label: '全部' },
  { key: 'PENDING_HR', label: '待 HR 审核', statuses: ['AI_PASSED'] },
  {
    key: 'IN_PROGRESS',
    label: '流程进行中',
    statuses: ['SUBMITTED', 'SCREENING', 'INTERVIEW_INVITED', 'INTERVIEWING', 'AI_REVIEWING'],
  },
  {
    key: 'PASSED',
    label: '已通过 / 线下邀约',
    statuses: ['HR_APPROVED', 'OFFLINE_SCHEDULED', 'OFFLINE_CONFIRMED'],
  },
  {
    key: 'REJECTED',
    label: '未通过',
    statuses: ['SCREEN_REJECTED', 'AI_REJECTED', 'HR_REJECTED'],
  },
  { key: 'EXPIRED', label: '已过期', statuses: ['EXPIRED'] },
];

type RiskFilterKey = 'ALL' | 'ATTENTION' | 'HIGH';

const RISK_FILTERS: { key: RiskFilterKey; label: string; levels?: ProctorRiskLevel[] }[] = [
  { key: 'ALL', label: '全部作答行为' },
  { key: 'ATTENTION', label: '需关注（中 / 高风险）', levels: ['MEDIUM', 'HIGH'] },
  { key: 'HIGH', label: '仅高风险', levels: ['HIGH'] },
];

const PAGE_SIZE = 20;

export default function ApplicationListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const currentUser = getStoredUser();
  const canWrite = currentUser?.role === 'ADMIN' || currentUser?.role === 'HR';

  const initialJobId = searchParams.get('jobId');
  const [jobs, setJobs] = useState<JobPosition[]>([]);
  const [jobId, setJobId] = useState<string>(initialJobId ?? '');
  const [groupKey, setGroupKey] = useState<StatusGroupKey>('ALL');
  const [riskFilter, setRiskFilter] = useState<RiskFilterKey>('ALL');
  const [keyword, setKeyword] = useState('');
  const [appliedKeyword, setAppliedKeyword] = useState('');
  const [page, setPage] = useState(1);

  const [items, setItems] = useState<ApplicationListItem[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [selectedIds, setSelectedIds] = useState<number[]>([]);
  const [batchComment, setBatchComment] = useState('');
  const [batchActing, setBatchActing] = useState(false);

  useEffect(() => {
    jobApi.list().then(setJobs).catch(() => {});
  }, []);

  const currentStatuses = useMemo(
    () => STATUS_GROUPS.find(g => g.key === groupKey)?.statuses,
    [groupKey],
  );

  const currentRiskLevels = useMemo(
    () => RISK_FILTERS.find(f => f.key === riskFilter)?.levels,
    [riskFilter],
  );

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const parsedJobId = jobId ? Number(jobId) : undefined;
      const res = await applicationApi.list({
        jobId: parsedJobId && !Number.isNaN(parsedJobId) ? parsedJobId : undefined,
        statuses: currentStatuses,
        riskLevels: currentRiskLevels,
        keyword: appliedKeyword || undefined,
        page,
        size: PAGE_SIZE,
      });
      setItems(res.items);
      setTotal(res.total);
      setSelectedIds([]);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [jobId, currentStatuses, currentRiskLevels, appliedKeyword, page]);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const showNotice = (msg: string) => {
    setNotice(msg);
    window.setTimeout(() => setNotice(null), 3000);
  };

  const handleJobChange = (value: string) => {
    setJobId(value);
    setPage(1);
    const next = new URLSearchParams(searchParams);
    if (value) {
      next.set('jobId', value);
    } else {
      next.delete('jobId');
    }
    setSearchParams(next, { replace: true });
  };

  const toggleSelect = (id: number) => {
    setSelectedIds(prev => (prev.includes(id) ? prev.filter(x => x !== id) : [...prev, id]));
  };

  const selectableIds = useMemo(
    () => items.filter(i => i.status === 'AI_PASSED').map(i => i.id),
    [items],
  );

  const toggleSelectAll = () => {
    if (selectableIds.length === 0) return;
    const allSelected = selectableIds.every(id => selectedIds.includes(id));
    setSelectedIds(allSelected ? [] : selectableIds);
  };

  const handleBatchReview = async (approve: boolean) => {
    if (selectedIds.length === 0) return;
    setBatchActing(true);
    setError(null);
    try {
      const res = await applicationApi.batchReview(
        selectedIds,
        approve,
        batchComment.trim() || undefined,
      );
      setBatchComment('');
      const failTip = res.failed.length > 0 ? `，失败 ${res.failed.length} 条` : '';
      showNotice(`已处理 ${res.succeeded.length} 条投递${failTip}`);
      await loadData();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setBatchActing(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">投递管理</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            查看候选人简历初筛、在线 AI 面试评分，执行 HR 审核与线下面试邀约 · 共 {total} 条投递
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Link
            to={
              jobId
                ? `${ROUTES.adminInvitations}?jobId=${jobId}${canWrite ? '&invite=1' : ''}`
                : `${ROUTES.adminInvitations}${canWrite ? '?invite=1' : ''}`
            }
            className="btn-primary flex items-center gap-2 rounded-xl px-4 py-2 text-sm"
          >
            <Send className="h-4 w-4" />
            {canWrite ? '邀请面试' : '查看面试邀请'}
          </Link>
          <button
            type="button"
            onClick={loadData}
            className="btn-secondary flex items-center gap-2 rounded-xl px-4 py-2 text-sm"
          >
            <RefreshCw className="h-4 w-4" />
            刷新
          </button>
        </div>
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

      {/* 筛选栏 */}
      <div className="dark-card space-y-4 p-4">
        <div className="flex flex-wrap gap-2">
          {STATUS_GROUPS.map(g => (
            <button
              key={g.key}
              type="button"
              onClick={() => {
                setGroupKey(g.key);
                setPage(1);
              }}
              className={`rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
                groupKey === g.key
                  ? 'bg-primary-500 text-white'
                  : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700'
              }`}
            >
              {g.label}
            </button>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <select
            value={jobId}
            onChange={e => handleJobChange(e.target.value)}
            className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            <option value="">全部岗位</option>
            {jobs.map(j => (
              <option key={j.id} value={j.id}>
                {j.title}
              </option>
            ))}
          </select>

          <select
            value={riskFilter}
            onChange={e => {
              setRiskFilter(e.target.value as RiskFilterKey);
              setPage(1);
            }}
            data-testid="risk-filter"
            className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            {RISK_FILTERS.map(f => (
              <option key={f.key} value={f.key}>
                {f.label}
              </option>
            ))}
          </select>

          <form
            onSubmit={e => {
              e.preventDefault();
              setAppliedKeyword(keyword.trim());
              setPage(1);
            }}
            className="flex flex-1 items-center gap-2 min-w-[220px]"
          >
            <div className="relative flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
              <input
                value={keyword}
                onChange={e => setKeyword(e.target.value)}
                placeholder="搜索姓名、邮箱、手机号…"
                className="w-full rounded-xl border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm text-slate-800 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
              />
            </div>
            <button type="submit" className="btn-secondary rounded-xl px-4 py-2 text-sm">
              查询
            </button>
          </form>
        </div>
      </div>

      {/* 批量审核工具栏（仅待 HR 审核记录可选） */}
      {canWrite && selectedIds.length > 0 && (
        <div className="dark-card flex flex-wrap items-center gap-3 border border-primary-200 bg-primary-50/50 p-4 dark:border-primary-800 dark:bg-primary-900/20">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-200">
            已选 {selectedIds.length} 条待审核记录
          </span>
          <input
            value={batchComment}
            onChange={e => setBatchComment(e.target.value)}
            maxLength={500}
            placeholder="批量审核意见（可选）"
            className="min-w-[220px] flex-1 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-800 dark:text-white"
          />
          <button
            type="button"
            disabled={batchActing}
            onClick={() => handleBatchReview(true)}
            className="btn-primary flex items-center gap-1.5 rounded-xl px-4 py-1.5 text-sm disabled:opacity-60"
          >
            <CheckCircle2 className="h-4 w-4" />
            批量通过
          </button>
          <button
            type="button"
            disabled={batchActing}
            onClick={() => handleBatchReview(false)}
            className="flex items-center gap-1.5 rounded-xl bg-rose-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-rose-700 disabled:opacity-60"
          >
            <XCircle className="h-4 w-4" />
            批量不通过
          </button>
        </div>
      )}

      {/* 列表表格 */}
      <div className="dark-card overflow-hidden">
        {loading ? (
          <div className="flex justify-center py-20">
            <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
          </div>
        ) : items.length === 0 ? (
          <div className="py-16 text-center text-sm text-slate-500 dark:text-slate-400">
            暂无符合条件的投递记录
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-slate-100 bg-slate-50/70 text-xs font-semibold text-slate-500 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-400">
                <tr>
                  {canWrite && (
                    <th className="w-10 px-4 py-3">
                      <input
                        type="checkbox"
                        checked={selectableIds.length > 0 && selectableIds.every(id => selectedIds.includes(id))}
                        disabled={selectableIds.length === 0}
                        onChange={toggleSelectAll}
                        title="全选本页待 HR 审核记录"
                        className="h-4 w-4 accent-primary-500"
                      />
                    </th>
                  )}
                  <th className="px-4 py-3">候选人</th>
                  <th className="px-4 py-3">投递岗位</th>
                  <th className="px-4 py-3">当前状态</th>
                  <th className="px-4 py-3">简历初筛</th>
                  <th className="px-4 py-3">AI 面试评分</th>
                  <th className="px-4 py-3">作答行为</th>
                  <th className="px-4 py-3">投递时间</th>
                  <th className="px-4 py-3 text-right">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                {items.map(item => {
                  const selectable = item.status === 'AI_PASSED';
                  return (
                    <tr key={item.id} className="hover:bg-slate-50/70 dark:hover:bg-slate-800/40">
                      {canWrite && (
                        <td className="px-4 py-3">
                          <input
                            type="checkbox"
                            disabled={!selectable}
                            checked={selectedIds.includes(item.id)}
                            onChange={() => toggleSelect(item.id)}
                            className="h-4 w-4 accent-primary-500 disabled:opacity-30"
                          />
                        </td>
                      )}
                      <td className="px-4 py-3">
                        <div className="font-medium text-slate-900 dark:text-white">{item.candidateName}</div>
                        <div className="text-xs text-slate-500 dark:text-slate-400">
                          {item.candidateEmail || '—'}
                          {item.candidatePhone ? ` · ${item.candidatePhone}` : ''}
                        </div>
                      </td>
                      <td className="px-4 py-3 text-slate-700 dark:text-slate-200">
                        <div>{item.jobTitle}</div>
                        <div className="flex flex-wrap items-center gap-1.5 text-xs text-slate-400">
                          {item.candidateLevel && (
                            <span
                              data-testid={`level-cell-${item.id}`}
                              className="font-medium text-slate-600 dark:text-slate-300"
                            >
                              定位：{JOB_DIFFICULTY_LABELS[item.candidateLevel]}
                            </span>
                          )}
                          {item.candidateLevel && item.interviewMode && <span>·</span>}
                          {item.interviewMode && (
                            <span>{INTERVIEW_MODE_LABELS[item.interviewMode]}</span>
                          )}
                        </div>
                      </td>
                      <td className="px-4 py-3">
                        <span
                          className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium ${APPLICATION_STATUS_BADGES[item.status]}`}
                        >
                          {APPLICATION_STATUS_LABELS[item.status]}
                        </span>
                      </td>
                      <td className="px-4 py-3 text-slate-700 dark:text-slate-200">
                        {item.screeningScore != null ? `${item.screeningScore} 分` : '—'}
                      </td>
                      <td className="px-4 py-3 text-slate-700 dark:text-slate-200">
                        {item.aiScore != null ? (
                          <span className="font-semibold">{item.aiScore} 分</span>
                        ) : (
                          '—'
                        )}
                        {item.writtenScore != null && (
                          <div className="text-xs text-slate-400">笔试 {item.writtenScore} 分</div>
                        )}
                      </td>
                      <td className="px-4 py-3">
                        {item.proctorRiskLevel ? (
                          <span
                            data-testid={`risk-cell-${item.id}`}
                            className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium ${PROCTOR_RISK_BADGES[item.proctorRiskLevel]}`}
                          >
                            {PROCTOR_RISK_LABELS[item.proctorRiskLevel]}
                            {item.proctorRiskScore != null ? ` · ${item.proctorRiskScore}` : ''}
                          </span>
                        ) : (
                          <span className="text-slate-400">—</span>
                        )}
                      </td>
                      <td className="px-4 py-3 text-xs text-slate-500 dark:text-slate-400">
                        {formatDateTime(item.submittedAt)}
                      </td>
                      <td className="px-4 py-3 text-right">
                        <Link
                          to={ROUTES.adminApplicationDetail(item.id)}
                          className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs font-medium text-primary-600 hover:bg-primary-50 dark:text-primary-400 dark:hover:bg-primary-900/30"
                        >
                          {item.status === 'AI_PASSED' && canWrite ? '去审核' : '查看详情'}
                          <ExternalLink className="h-3.5 w-3.5" />
                        </Link>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}

        {/* 分页 */}
        {total > PAGE_SIZE && (
          <div className="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-500 dark:border-slate-800 dark:text-slate-400">
            <span>
              第 {page} / {totalPages} 页 · 共 {total} 条
            </span>
            <div className="flex items-center gap-2">
              <button
                type="button"
                disabled={page <= 1}
                onClick={() => setPage(p => Math.max(1, p - 1))}
                className="btn-secondary flex items-center gap-1 rounded-lg px-2.5 py-1 disabled:opacity-40"
              >
                <ChevronLeft className="h-3.5 w-3.5" />
                上一页
              </button>
              <button
                type="button"
                disabled={page >= totalPages}
                onClick={() => setPage(p => Math.min(totalPages, p + 1))}
                className="btn-secondary flex items-center gap-1 rounded-lg px-2.5 py-1 disabled:opacity-40"
              >
                下一页
                <ChevronRight className="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
