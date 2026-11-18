import {Link, Outlet, useLocation, useNavigate} from 'react-router-dom';
import {motion} from 'framer-motion';
import {
  BarChart3,
  BookOpen,
  Briefcase,
  Calendar,
  ChevronRight,
  ClipboardCheck,
  Database,
  LogOut,
  Mail,
  Moon,
  Settings,
  Sparkles,
  Sun,
  UserCog,
} from 'lucide-react';
import {useTheme} from '../hooks/useTheme';
import {useEffect, useState} from 'react';
import UnifiedInterviewModal, {UnifiedInterviewConfig} from './UnifiedInterviewModal';
import {ADMIN_HOME_PATH, ROUTES} from '../constants/routes';
import {ADMIN_LOGIN_PATH, AUTH_CHANGED_EVENT, clearAuth, getStoredUser} from '../utils/authStorage';
import {ADMIN_ROLE_LABELS, type AdminRole, type AdminUser} from '../types/auth';

interface NavItem {
  id: string;
  path: string;
  label: string;
  icon: React.ComponentType<{ className?: string }>;
  description?: string;
  /** 仅这些角色可见；为空表示所有后台角色可见 */
  roles?: AdminRole[];
}

interface NavGroup {
  id: string;
  title: string;
  items: NavItem[];
}

export default function Layout() {
  const location = useLocation();
  const currentPath = location.pathname;
  const {theme, toggleTheme} = useTheme();
  const navigate = useNavigate();
  const [currentUser, setCurrentUser] = useState<AdminUser | null>(getStoredUser);

  useEffect(() => {
    const sync = () => setCurrentUser(getStoredUser());
    window.addEventListener(AUTH_CHANGED_EVENT, sync);
    window.addEventListener('storage', sync);
    return () => {
      window.removeEventListener(AUTH_CHANGED_EVENT, sync);
      window.removeEventListener('storage', sync);
    };
  }, []);

  const handleLogout = () => {
    clearAuth();
    navigate(ADMIN_LOGIN_PATH, {replace: true});
  };
  const [interviewModalPreset, setInterviewModalPreset] = useState<{
    defaultMode: 'text' | 'voice';
    defaultResumeId?: number;
    title: string;
    subtitle: string;
    startButtonText: string;
  } | null>(null);

  const openInterviewModalWithResume = (resumeId: number) => {
    setInterviewModalPreset({
      defaultMode: 'text',
      defaultResumeId: resumeId,
      title: '开始模拟面试',
      subtitle: '配置面试参数，开始练习',
      startButtonText: '开始面试',
    });
  };

  const handleInterviewStart = (config: UnifiedInterviewConfig) => {
    setInterviewModalPreset(null);
    if (config.mode === 'text') {
      navigate(ROUTES.interviewCreate(crypto.randomUUID()), {
        state: {
          resumeId: config.resumeId,
          interviewConfig: {
            skillId: config.skillId,
            difficulty: config.difficulty,
            questionCount: config.questionCount,
            llmProvider: config.llmProvider,
          },
        },
      });
      return;
    }

    const params = new URLSearchParams({
      skillId: config.skillId,
      difficulty: config.difficulty,
    });
    navigate(`/voice-interview?${params.toString()}`, {
      state: {
        voiceConfig: {
          skillId: config.skillId,
          difficulty: config.difficulty,
          techEnabled: true,
          projectEnabled: true,
          hrEnabled: true,
          plannedDuration: config.plannedDuration,
          resumeId: config.resumeId,
          llmProvider: config.llmProvider,
        },
      },
    });
  };

  // 按企业招聘业务组织的导航项（隐藏个人练习入口）
  const navGroups: NavGroup[] = [
    {
      id: 'recruitment',
      title: '招聘管理',
      items: [
        {
          id: 'admin-applications',
          path: ROUTES.adminApplications,
          label: '投递管理',
          icon: ClipboardCheck,
          description: '候选人审核与线下邀约',
        },
        {
          id: 'admin-invitations',
          path: ROUTES.adminInvitations,
          label: '面试邀请',
          icon: Mail,
          description: '定向邮箱邀请与参面状态',
        },
        {
          id: 'admin-jobs',
          path: ROUTES.adminJobs,
          label: '岗位管理',
          icon: Briefcase,
          description: '配置招聘岗位与面试',
        },
        {
          id: 'admin-dashboard',
          path: ROUTES.adminDashboard,
          label: '招聘看板',
          icon: BarChart3,
          description: '全流程漏斗与状态统计',
          roles: ['ADMIN', 'HR'],
        },
        {
          id: 'interview-schedule',
          path: '/interview-schedule',
          label: '面试日程',
          icon: Calendar,
          description: '线下面试日程日历',
        },
      ],
    },
    {
      id: 'knowledge',
      title: '题库管理',
      items: [
        {
          id: 'kb-manage',
          path: '/knowledgebase',
          label: '知识库文档',
          icon: Database,
          description: '上传岗位参考资料',
          roles: ['ADMIN', 'HR'],
        },
        {
          id: 'kb-interview',
          path: '/knowledgebase-interview',
          label: '面试题库',
          icon: BookOpen,
          description: '维护岗位专属题库',
          roles: ['ADMIN', 'HR'],
        },
      ],
    },
    {
      id: 'system',
      title: '系统配置',
      items: [
        {
          id: 'admin-mail',
          path: ROUTES.adminMail,
          label: '邮件设置',
          icon: Mail,
          description: 'SMTP、模板与发送日志',
          roles: ['ADMIN'],
        },
        {
          id: 'settings',
          path: '/settings',
          label: '模型与语音',
          icon: Settings,
          description: '管理模型和语音服务',
          roles: ['ADMIN'],
        },
        {
          id: 'account',
          path: ROUTES.adminAccount,
          label: '账号管理',
          icon: UserCog,
          description: '密码与后台账号',
        },
      ],
    },
  ];

  const visibleNavGroups = navGroups
    .map(group => ({
      ...group,
      items: group.items.filter(item => !item.roles || (currentUser != null && item.roles.includes(currentUser.role))),
    }))
    .filter(group => group.items.length > 0);

  // 判断当前页面是否匹配导航项
  const isActive = (path: string) => {
    if (path.startsWith('#')) return false;
    if (path === ROUTES.adminApplications) {
      return currentPath === '/' || currentPath.startsWith(ROUTES.adminApplications);
    }
    if (path === '/knowledgebase') {
      return currentPath === '/knowledgebase' || currentPath === '/knowledgebase/upload';
    }
    return currentPath.startsWith(path);
  };

  return (
    <div className="flex min-h-screen bg-gradient-to-br from-slate-50 to-indigo-50 dark:from-slate-900 dark:to-slate-800">
      {/* 左侧边栏 */}
      <aside className="w-64 bg-white dark:bg-slate-900 border-r border-slate-100 dark:border-slate-700 fixed h-screen left-0 top-0 z-50 flex flex-col">
        {/* Logo */}
        <div className="p-6 border-b border-slate-100 dark:border-slate-700 flex items-center justify-between">
          <Link to={ADMIN_HOME_PATH} className="flex items-center gap-3">
            <div className="w-10 h-10 bg-gradient-to-br from-primary-500 to-primary-600 rounded-xl flex items-center justify-center text-white shadow-lg shadow-primary-500/30">
              <Sparkles className="w-5 h-5" />
            </div>
            <div>
              <span className="text-lg font-bold text-slate-800 dark:text-white tracking-tight block">AI Interview</span>
              <span className="text-xs text-slate-400 dark:text-slate-500">智能招聘后台</span>
            </div>
          </Link>
        </div>

        {/* 主题切换按钮 */}
        <div className="px-4 pb-2">
          <button
            onClick={toggleTheme}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-lg bg-slate-100 dark:bg-slate-800 text-slate-600 dark:text-slate-300 hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
          >
            {theme === 'dark' ? (
              <>
                <Sun className="w-4 h-4" />
                <span className="text-sm font-medium">浅色模式</span>
              </>
            ) : (
              <>
                <Moon className="w-4 h-4" />
                <span className="text-sm font-medium">深色模式</span>
              </>
            )}
          </button>
        </div>

        {/* 导航菜单 */}
        <nav className="flex-1 p-4 overflow-y-auto">
          <div className="space-y-6">
            {visibleNavGroups.map((group) => (
              <div key={group.id}>
                <div className="px-3 mb-2">
                  <span className="text-xs font-semibold text-slate-400 dark:text-slate-500 uppercase tracking-wider">
                    {group.title}
                  </span>
                </div>
                <div className="space-y-1">
                  {group.items.map((item) => {
                    const active = isActive(item.path);

                    return (
                      <Link
                        key={item.id}
                        to={item.path}
                        className={`group relative flex items-center gap-3 px-3 py-2.5 rounded-xl transition-all duration-200
                          ${active
                            ? 'bg-primary-50 dark:bg-primary-900/30 text-primary-600 dark:text-primary-400'
                            : 'text-slate-600 dark:text-slate-400 hover:bg-slate-50 dark:hover:bg-slate-800 hover:text-slate-900 dark:hover:text-white'
                          }`}
                      >
                        <div className={`w-9 h-9 rounded-lg flex items-center justify-center transition-colors
                          ${active
                            ? 'bg-primary-100 dark:bg-primary-900/50 text-primary-600 dark:text-primary-400'
                            : 'bg-slate-100 dark:bg-slate-800 text-slate-500 dark:text-slate-400 group-hover:bg-slate-200 dark:group-hover:bg-slate-700 group-hover:text-slate-700 dark:group-hover:text-white'
                          }`}
                        >
                          <item.icon className="w-5 h-5" />
                        </div>
                        <div className="flex-1 min-w-0">
                          <span className={`text-sm block ${active ? 'font-semibold' : 'font-medium'}`}>
                            {item.label}
                          </span>
                          {item.description && (
                            <span className="text-xs text-slate-400 dark:text-slate-500 truncate block">
                              {item.description}
                            </span>
                          )}
                        </div>
                        {active && <ChevronRight className="w-4 h-4 text-primary-400" />}
                      </Link>
                    );
                  })}
                </div>
              </div>
            ))}
          </div>
        </nav>

        {/* 底部信息：当前账号 */}
        <div className="p-4 border-t border-slate-100 dark:border-slate-700">
          <div className="px-3 py-2 bg-gradient-to-r from-primary-50 to-indigo-50 dark:from-primary-900/30 dark:to-slate-800 rounded-xl flex items-center gap-2">
            <div className="flex-1 min-w-0">
              <p className="text-sm text-slate-800 dark:text-white font-medium truncate">
                {currentUser?.displayName || currentUser?.username || '未登录'}
              </p>
              <p className="text-xs text-primary-600 dark:text-primary-400 mt-0.5">
                {currentUser ? ADMIN_ROLE_LABELS[currentUser.role] : ''}
              </p>
            </div>
            <button
              onClick={handleLogout}
              title="退出登录"
              className="p-2 rounded-lg text-slate-500 hover:text-red-600 hover:bg-white/70 dark:text-slate-400 dark:hover:bg-slate-700 transition-colors"
            >
              <LogOut className="w-4 h-4" />
            </button>
          </div>
        </div>
      </aside>

      {/* 主内容区 */}
      <main className="flex-1 ml-64 p-10 min-h-screen overflow-y-auto">
        <motion.div
          key={currentPath}
          initial={{ opacity: 0, y: 20 }}
          animate={{ opacity: 1, y: 0 }}
          exit={{ opacity: 0, y: -20 }}
          transition={{ duration: 0.3 }}
        >
          <Outlet context={{ openInterviewModalWithResume }} />
        </motion.div>
      </main>

      {/* 统一面试弹窗 */}
      <UnifiedInterviewModal
        isOpen={interviewModalPreset !== null}
        onClose={() => setInterviewModalPreset(null)}
        onStart={handleInterviewStart}
        defaultMode={interviewModalPreset?.defaultMode || 'text'}
        defaultResumeId={interviewModalPreset?.defaultResumeId}
        hideModeSwitch={interviewModalPreset?.defaultResumeId == null}
        title={interviewModalPreset?.title || '开始模拟面试'}
        subtitle={interviewModalPreset?.subtitle || '选择面试模式和主题，快速开始'}
        startButtonText={interviewModalPreset?.startButtonText || '开始面试'}
      />
    </div>
  );
}
