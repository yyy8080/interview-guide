import { useEffect, useState } from 'react';
import { Navigate, Outlet, useLocation } from 'react-router-dom';
import { AUTH_CHANGED_EVENT, ADMIN_LOGIN_PATH, getStoredUser, isAuthenticated } from '../utils/authStorage';
import type { AdminRole } from '../types/auth';

interface RequireAuthProps {
  /** 允许访问的角色；为空表示任意已登录账号 */
  roles?: AdminRole[];
  children?: React.ReactNode;
}

/**
 * 后台路由守卫：未登录跳转登录页；角色不符显示无权限提示
 */
export default function RequireAuth({ roles, children }: RequireAuthProps) {
  const location = useLocation();
  const [authed, setAuthed] = useState(isAuthenticated);

  useEffect(() => {
    const sync = () => setAuthed(isAuthenticated());
    window.addEventListener(AUTH_CHANGED_EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  if (!authed) {
    const redirect = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`${ADMIN_LOGIN_PATH}?redirect=${redirect}`} replace />;
  }

  if (roles && roles.length > 0) {
    const user = getStoredUser();
    if (!user || !roles.includes(user.role)) {
      return (
        <div className="flex min-h-[50vh] flex-col items-center justify-center gap-2 text-center">
          <p className="text-lg font-semibold text-slate-800 dark:text-slate-100">没有访问权限</p>
          <p className="text-sm text-slate-500 dark:text-slate-400">当前账号角色无法访问该页面，请联系管理员</p>
        </div>
      );
    }
  }

  return children ? <>{children}</> : <Outlet />;
}
