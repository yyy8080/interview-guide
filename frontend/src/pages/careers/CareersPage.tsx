import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { Briefcase, ChevronRight, Clock, Loader2, MapPin, Users } from 'lucide-react';
import { publicJobApi } from '../../api/job';
import { getErrorMessage } from '../../api/request';
import { ROUTES } from '../../constants/routes';
import { formatDateTime } from '../../utils/date';
import { EMPLOYMENT_TYPE_LABELS, INTERVIEW_MODE_LABELS, type PublicJob } from '../../types/job';

export default function CareersPage() {
  const [jobs, setJobs] = useState<PublicJob[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    publicJobApi.list()
      .then(setJobs)
      .catch(err => setError(getErrorMessage(err)))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-slate-900 dark:text-white sm:text-3xl">开放职位</h1>
        <p className="mt-2 text-sm text-slate-500 dark:text-slate-400">
          实行受邀面试制：收到面试邀请邮件的候选人可验证受邀邮箱投递对应岗位简历并完成在线 AI 面试（非受邀邮箱不允许投递简历与参加面试）
        </p>
      </div>

      {error && <div className="rounded-xl bg-red-50 px-4 py-2 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">{error}</div>}

      {loading ? (
        <div className="flex justify-center py-20"><Loader2 className="h-8 w-8 animate-spin text-primary-500" /></div>
      ) : jobs.length === 0 ? (
        <div className="dark-card flex flex-col items-center gap-3 py-16 text-center">
          <Briefcase className="h-10 w-10 text-slate-300 dark:text-slate-600" />
          <p className="text-slate-500 dark:text-slate-400">暂无开放职位，欢迎稍后再来</p>
        </div>
      ) : (
        <div className="grid gap-3">
          {jobs.map(job => (
            <Link
              key={job.id}
              to={ROUTES.careerJob(job.id)}
              className="dark-card group flex items-center gap-3 p-4 transition-shadow hover:shadow-md sm:gap-4 sm:p-5"
            >
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h2 className="text-base font-semibold text-slate-900 sm:text-lg group-hover:text-primary-600 dark:text-white dark:group-hover:text-primary-400">
                    {job.title}
                  </h2>
                  {job.salaryRange && <span className="text-sm font-semibold text-primary-600 dark:text-primary-400">{job.salaryRange}</span>}
                </div>
                <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-slate-500 dark:text-slate-400">
                  {job.department && <span>{job.department}</span>}
                  {job.location && <span className="flex items-center gap-1"><MapPin className="h-3.5 w-3.5" />{job.location}</span>}
                  <span>{EMPLOYMENT_TYPE_LABELS[job.employmentType]}</span>
                  {job.headcount && <span className="flex items-center gap-1"><Users className="h-3.5 w-3.5" />招 {job.headcount} 人</span>}
                  <span>{INTERVIEW_MODE_LABELS[job.interviewMode]}</span>
                  {job.deadline && <span className="flex items-center gap-1"><Clock className="h-3.5 w-3.5" />截止 {formatDateTime(job.deadline)}</span>}
                </div>
              </div>
              <ChevronRight className="h-5 w-5 shrink-0 text-slate-300 group-hover:text-primary-500" />
            </Link>
          ))}
        </div>
      )}
    </div>
  );
}
