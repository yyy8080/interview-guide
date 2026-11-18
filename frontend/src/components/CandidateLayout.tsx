import { useEffect, useState } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Briefcase, FileText, LogIn, LogOut, Search } from 'lucide-react';
import { ROUTES } from '../constants/routes';
import type { CandidateProfile } from '../types/recruitment';
import {
  CANDIDATE_AUTH_CHANGED_EVENT,
  clearCandidateAuth,
  getStoredCandidate,
} from '../utils/candidateStorage';

/**
 * 候选人门户布局：仅提供岗位浏览、简历投递与在线面试进度功能
 */
export default function CandidateLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const [candidate, setCandidate] = useState<CandidateProfile | null>(getStoredCandidate);

  useEffect(() => {
    const sync = () => setCandidate(getStoredCandidate());
    window.addEventListener(CANDIDATE_AUTH_CHANGED_EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(CANDIDATE_AUTH_CHANGED_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  const handleLogout = () => {
    clearCandidateAuth();
    navigate(ROUTES.careers);
  };

  return (
    <div className="candidate-portal flex min-h-screen flex-col bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800">
      <header className="sticky top-0 z-40 border-b border-slate-100 bg-white/85 pt-[env(safe-area-inset-top)] backdrop-blur dark:border-slate-700 dark:bg-slate-900/85">
        <div className="mx-auto flex h-14 max-w-5xl items-center justify-between gap-2 px-3 sm:h-16 sm:px-6">
          <Link to={ROUTES.careers} className="flex min-w-0 items-center gap-2 sm:gap-2.5">
            <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-xl bg-gradient-to-br from-primary-500 to-primary-600 text-white shadow-lg shadow-primary-500/30 sm:h-9 sm:w-9">
              <Briefcase className="h-4 w-4 sm:h-5 sm:w-5" />
            </div>
            <span className="hidden text-base font-bold tracking-tight text-slate-800 min-[400px]:inline sm:text-lg dark:text-white">加入我们</span>
          </Link>

          <nav className="flex items-center gap-1 text-sm sm:gap-4">
            <Link
              to={ROUTES.careers}
              className={`flex items-center gap-1 whitespace-nowrap rounded-lg px-2.5 py-1.5 font-medium transition sm:px-3 ${
                location.pathname === ROUTES.careers
                  ? 'bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-400'
                  : 'text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white'
              }`}
            >
              <Search className="h-4 w-4 sm:hidden" />
              <span className="sm:hidden">职位</span>
              <span className="hidden sm:inline">热招职位</span>
            </Link>
            <Link
              to={ROUTES.careerMe}
              className={`flex items-center gap-1 whitespace-nowrap rounded-lg px-2.5 py-1.5 font-medium transition sm:px-3 ${
                location.pathname.startsWith(ROUTES.careerMe)
                  ? 'bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-400'
                  : 'text-slate-600 hover:text-slate-900 dark:text-slate-300 dark:hover:text-white'
              }`}
            >
              <FileText className="h-4 w-4" />
              我的投递
            </Link>

            {candidate ? (
              <div className="flex items-center gap-2 border-l border-slate-200 pl-2 sm:pl-3 dark:border-slate-700">
                <span className="hidden max-w-[180px] truncate text-xs text-slate-500 sm:inline dark:text-slate-400">
                  {candidate.email}
                </span>
                <button
                  type="button"
                  onClick={handleLogout}
                  title="退出登录"
                  className="flex items-center gap-1 whitespace-nowrap rounded-lg px-2 py-1.5 text-xs text-slate-500 hover:bg-slate-100 hover:text-rose-600 dark:text-slate-400 dark:hover:bg-slate-800"
                >
                  <LogOut className="h-3.5 w-3.5" />
                  退出
                </button>
              </div>
            ) : (
              <Link
                to={ROUTES.careerLogin}
                className="btn-primary flex items-center gap-1.5 whitespace-nowrap rounded-xl px-3 py-1.5 text-xs font-medium sm:px-3.5"
              >
                <LogIn className="h-3.5 w-3.5" />
                <span className="sm:hidden">登录</span>
                <span className="hidden sm:inline">邮箱验证登录</span>
              </Link>
            )}
          </nav>
        </div>
      </header>
      <main className="mx-auto w-full max-w-5xl flex-1 px-3 py-5 sm:px-6 sm:py-10">
        <Outlet />
      </main>
      <footer className="px-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))] pt-4 text-center text-xs text-slate-400 dark:text-slate-500">
        简历与面试记录仅用于本次岗位招聘评估
      </footer>
    </div>
  );
}
