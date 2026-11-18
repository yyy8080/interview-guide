import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bar, BarChart, CartesianGrid, Cell, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { CalendarCheck2, Clock, FileCheck2, Loader2, RefreshCw, Users, XCircle } from 'lucide-react';
import { applicationApi } from '../../api/application';
import { jobApi } from '../../api/job';
import { getErrorMessage } from '../../api/request';
import { ROUTES } from '../../constants/routes';
import type { JobPosition } from '../../types/job';
import {
  APPLICATION_STATUS_BADGES,
  APPLICATION_STATUS_LABELS,
  type ApplicationStats,
  type ApplicationStatus,
} from '../../types/recruitment';

const FUNNEL_COLORS = ['#6366f1', '#4f46e5', '#0ea5e9', '#10b981', '#059669', '#0d9488'];

export default function DashboardPage() {
  const [jobs, setJobs] = useState<JobPosition[]>([]);
  const [jobId, setJobId] = useState<string>('');
  const [stats, setStats] = useState<ApplicationStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    jobApi.list().then(setJobs).catch(() => {});
  }, []);

  const loadStats = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const parsed = jobId ? Number(jobId) : undefined;
      const data = await applicationApi.stats(parsed && !Number.isNaN(parsed) ? parsed : undefined);
      setStats(data);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [jobId]);

  useEffect(() => {
    loadStats();
  }, [loadStats]);

  const kpis = useMemo(() => {
    const s = stats?.byStatus;
    if (!s || !stats) {
      return { total: 0, pendingHr: 0, interviewing: 0, offline: 0, rejected: 0 };
    }
    return {
      total: stats.total,
      pendingHr: s.AI_PASSED ?? 0,
      interviewing: (s.INTERVIEW_INVITED ?? 0) + (s.INTERVIEWING ?? 0) + (s.AI_REVIEWING ?? 0),
      offline: (s.OFFLINE_SCHEDULED ?? 0) + (s.OFFLINE_CONFIRMED ?? 0),
      rejected: (s.SCREEN_REJECTED ?? 0) + (s.AI_REJECTED ?? 0) + (s.HR_REJECTED ?? 0),
    };
  }, [stats]);

  const funnelData = useMemo(() => {
    if (!stats) return [];
    const base = stats.total || 1;
    return stats.funnel.map(stage => ({
      ...stage,
      rate: stats.total > 0 ? Math.round((stage.count / base) * 100) : 0,
    }));
  }, [stats]);

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">招聘看板</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            从简历投递、AI 初筛、在线面试到线下邀约的全流程转化概览
          </p>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={jobId}
            onChange={e => setJobId(e.target.value)}
            className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            <option value="">全部岗位</option>
            {jobs.map(j => (
              <option key={j.id} value={j.id}>
                {j.title}
              </option>
            ))}
          </select>
          <button
            type="button"
            onClick={loadStats}
            className="btn-secondary flex items-center gap-1.5 rounded-xl px-3 py-2 text-sm"
          >
            <RefreshCw className="h-4 w-4" />
            刷新
          </button>
        </div>
      </div>

      {error && (
        <div className="rounded-xl bg-red-50 px-4 py-2.5 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">
          {error}
        </div>
      )}

      {loading ? (
        <div className="flex justify-center py-20">
          <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
        </div>
      ) : (
        <>
          {/* KPI 卡片 */}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-5">
            <div className="dark-card p-5">
              <div className="flex items-center justify-between text-slate-500 dark:text-slate-400">
                <span className="text-xs font-medium">累计投递</span>
                <Users className="h-4 w-4 text-indigo-500" />
              </div>
              <div className="mt-2 text-2xl font-bold text-slate-900 dark:text-white">{kpis.total}</div>
            </div>

            <Link to={ROUTES.adminApplications} className="dark-card block p-5 transition hover:border-primary-300">
              <div className="flex items-center justify-between text-slate-500 dark:text-slate-400">
                <span className="text-xs font-medium">待 HR 审核</span>
                <FileCheck2 className="h-4 w-4 text-amber-500" />
              </div>
              <div className="mt-2 text-2xl font-bold text-amber-600 dark:text-amber-400">{kpis.pendingHr}</div>
            </Link>

            <div className="dark-card p-5">
              <div className="flex items-center justify-between text-slate-500 dark:text-slate-400">
                <span className="text-xs font-medium">在线面试 / AI 审核中</span>
                <Clock className="h-4 w-4 text-sky-500" />
              </div>
              <div className="mt-2 text-2xl font-bold text-slate-900 dark:text-white">{kpis.interviewing}</div>
            </div>

            <div className="dark-card p-5">
              <div className="flex items-center justify-between text-slate-500 dark:text-slate-400">
                <span className="text-xs font-medium">已邀约线下面试</span>
                <CalendarCheck2 className="h-4 w-4 text-emerald-500" />
              </div>
              <div className="mt-2 text-2xl font-bold text-emerald-600 dark:text-emerald-400">{kpis.offline}</div>
            </div>

            <div className="dark-card p-5">
              <div className="flex items-center justify-between text-slate-500 dark:text-slate-400">
                <span className="text-xs font-medium">未通过</span>
                <XCircle className="h-4 w-4 text-rose-500" />
              </div>
              <div className="mt-2 text-2xl font-bold text-slate-900 dark:text-white">{kpis.rejected}</div>
            </div>
          </div>

          {/* 漏斗图 */}
          <div className="grid gap-6 lg:grid-cols-3">
            <div className="dark-card p-6 lg:col-span-2">
              <h2 className="mb-4 text-base font-semibold text-slate-900 dark:text-white">招聘转化漏斗</h2>
              <div className="h-72 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <BarChart data={funnelData} layout="vertical" margin={{ left: 20, right: 24, top: 4, bottom: 4 }}>
                    <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="#e2e8f0" />
                    <XAxis type="number" allowDecimals={false} />
                    <YAxis type="category" dataKey="label" width={104} tick={{ fontSize: 12 }} />
                    <Tooltip
                      formatter={(value) => [`${value ?? 0} 人`, '累计到达']}
                    />
                    <Bar dataKey="count" radius={[0, 8, 8, 0]} barSize={26}>
                      {funnelData.map((entry, idx) => (
                        <Cell key={entry.key} fill={FUNNEL_COLORS[idx % FUNNEL_COLORS.length]} />
                      ))}
                    </Bar>
                  </BarChart>
                </ResponsiveContainer>
              </div>
            </div>

            {/* 状态分布 */}
            <div className="dark-card p-6">
              <h2 className="mb-4 text-base font-semibold text-slate-900 dark:text-white">当前状态分布</h2>
              <div className="space-y-2.5">
                {(Object.keys(APPLICATION_STATUS_LABELS) as ApplicationStatus[]).map(status => {
                  const count = stats?.byStatus?.[status] ?? 0;
                  return (
                    <div key={status} className="flex items-center justify-between text-sm">
                      <span
                        className={`inline-flex rounded-full px-2.5 py-0.5 text-xs font-medium ${APPLICATION_STATUS_BADGES[status]}`}
                      >
                        {APPLICATION_STATUS_LABELS[status]}
                      </span>
                      <span className="font-medium text-slate-800 dark:text-slate-200">{count}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}
