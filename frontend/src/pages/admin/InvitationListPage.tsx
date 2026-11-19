import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import {
  AlertTriangle,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  ExternalLink,
  ImagePlus,
  Loader2,
  Mail,
  RefreshCw,
  RotateCw,
  Search,
  Send,
  UserCheck,
  UserX,
  X,
  XCircle,
} from 'lucide-react';
import { invitationApi } from '../../api/invitation';
import { jobApi } from '../../api/job';
import { getErrorMessage } from '../../api/request';
import BatchResumeImageInvitePanel, {
  isDraftEmailValid,
  normalizeDraftEmail,
  type RecognizedCandidateDraft,
} from '../../components/BatchResumeImageInvitePanel';
import ConfirmDialog from '../../components/ConfirmDialog';
import { ROUTES } from '../../constants/routes';
import { getStoredUser } from '../../utils/authStorage';
import { formatDateTime } from '../../utils/date';
import type { JobPosition } from '../../types/job';
import {
  EMAIL_LOG_STATUS_LABELS,
  INVITATION_PARTICIPATION_BADGES,
  INVITATION_PARTICIPATION_LABELS,
  type EmailLogStatus,
  type InvitationListItem,
  type InvitationParticipationFilter,
  type InvitationRecipientInput,
  type InvitationStats,
} from '../../types/recruitment';

type FilterTabKey = 'ALL' | InvitationParticipationFilter;
type InviteModalMode = 'manual' | 'image';

const MAX_BATCH_IMAGES = 20;
const UPLOAD_CHUNK_SIZE = 10;

const FILTER_TABS: { key: FilterTabKey; label: string }[] = [
  { key: 'ALL', label: '全部邀请' },
  { key: 'PARTICIPATED', label: '已参加面试' },
  { key: 'PENDING_INTERVIEW', label: '待参加 / 初筛中' },
  { key: 'NOT_APPLIED', label: '尚未投递简历' },
  { key: 'SCREEN_REJECTED', label: '初筛未通过' },
  { key: 'EXPIRED_OR_REVOKED', label: '已过期 / 已撤销' },
];

const EMAIL_STATUS_BADGES: Record<EmailLogStatus, string> = {
  SENT: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300',
  PENDING: 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
  SENDING: 'bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
  FAILED: 'bg-rose-50 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300',
};

const PAGE_SIZE = 20;

export default function InvitationListPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const user = useMemo(() => getStoredUser(), []);
  const canWrite = user?.role === 'ADMIN' || user?.role === 'HR';
  const isAdmin = user?.role === 'ADMIN';

  const initialJobId = searchParams.get('jobId') ?? '';
  const initialInviteParam = searchParams.get('invite');

  const [jobs, setJobs] = useState<JobPosition[]>([]);
  const [items, setItems] = useState<InvitationListItem[]>([]);
  const [stats, setStats] = useState<InvitationStats | null>(null);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(1);
  const [tab, setTab] = useState<FilterTabKey>('ALL');
  const [jobId, setJobId] = useState<string>(initialJobId);
  const [keywordInput, setKeywordInput] = useState('');
  const [keyword, setKeyword] = useState('');

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [mailWarning, setMailWarning] = useState<string | null>(null);

  // 发送邀请弹窗
  const [modalOpen, setModalOpen] = useState(false);
  const [inviteMode, setInviteMode] = useState<InviteModalMode>('manual');
  const [inviteJobId, setInviteJobId] = useState<string>(initialJobId);
  const [emailsText, setEmailsText] = useState('');
  const [candidateName, setCandidateName] = useState('');
  const [expireDays, setExpireDays] = useState(7);
  const [skipScreening, setSkipScreening] = useState(false);
  const [note, setNote] = useState('');
  const [sending, setSending] = useState(false);
  const [modalError, setModalError] = useState<string | null>(null);

  // 批量简历图片识别状态
  const [recognizedItems, setRecognizedItems] = useState<RecognizedCandidateDraft[]>([]);
  const [recognizing, setRecognizing] = useState(false);
  const [recognizeProgress, setRecognizeProgress] = useState<string | null>(null);
  const previewUrlsRef = useRef<Set<string>>(new Set());

  // 单行操作
  const [actingId, setActingId] = useState<number | null>(null);
  const [revokeTarget, setRevokeTarget] = useState<InvitationListItem | null>(null);

  const openJobs = useMemo(() => jobs.filter(j => j.status === 'OPEN'), [jobs]);

  useEffect(() => {
    const urls = previewUrlsRef.current;
    return () => {
      urls.forEach(u => URL.revokeObjectURL(u));
      urls.clear();
    };
  }, []);

  useEffect(() => {
    jobApi
      .list()
      .then(list => {
        setJobs(list);
        const open = list.filter(j => j.status === 'OPEN');
        setInviteJobId(prev => {
          if (prev && open.some(j => String(j.id) === prev)) {
            return prev;
          }
          return open[0] ? String(open[0].id) : '';
        });
      })
      .catch(() => {});
  }, []);

  useEffect(() => {
    if ((initialInviteParam === '1' || initialInviteParam === 'image') && canWrite) {
      setInviteMode(initialInviteParam === 'image' ? 'image' : 'manual');
      setModalOpen(true);
      const next = new URLSearchParams(searchParams);
      next.delete('invite');
      setSearchParams(next, { replace: true });
    }
  }, [initialInviteParam, canWrite, searchParams, setSearchParams]);

  const loadData = useCallback(async () => {
    setLoading(true);
    setError(null);
    const parsedJobId = jobId ? Number(jobId) : undefined;
    try {
      const [pageRes, statsRes] = await Promise.all([
        invitationApi.list({
          jobId: parsedJobId,
          participation: tab === 'ALL' ? undefined : tab,
          keyword: keyword || undefined,
          page,
          size: PAGE_SIZE,
        }),
        invitationApi.stats(parsedJobId),
      ]);
      setItems(pageRes.items);
      setTotal(pageRes.total);
      setStats(statsRes);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, [jobId, tab, keyword, page]);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const showNotice = (msg: string) => {
    setNotice(msg);
    window.setTimeout(() => setNotice(null), 4000);
  };

  const handleJobFilterChange = (val: string) => {
    setJobId(val);
    setPage(1);
    const next = new URLSearchParams(searchParams);
    if (val) {
      next.set('jobId', val);
    } else {
      next.delete('jobId');
    }
    setSearchParams(next, { replace: true });
  };

  const parsedEmailTokens = useMemo(() => {
    if (!emailsText.trim()) return [];
    const set = new Set<string>();
    emailsText
      .split(/[\s,;，；]+/)
      .map(s => s.trim().toLowerCase())
      .filter(Boolean)
      .forEach(s => set.add(s));
    return Array.from(set);
  }, [emailsText]);

  const selectedValidRecipients = useMemo(() => {
    const map = new Map<string, InvitationRecipientInput>();
    for (const item of recognizedItems) {
      if (!item.selected || !isDraftEmailValid(item.email)) continue;
      const email = normalizeDraftEmail(item.email);
      const name = item.candidateName.trim();
      const existing = map.get(email);
      if (!existing) {
        map.set(email, { email, candidateName: name || undefined });
      } else if (!existing.candidateName && name) {
        existing.candidateName = name;
      }
    }
    return Array.from(map.values());
  }, [recognizedItems]);

  const clearRecognizedItems = useCallback(() => {
    setRecognizedItems(prev => {
      prev.forEach(item => {
        if (item.previewUrl) {
          URL.revokeObjectURL(item.previewUrl);
          previewUrlsRef.current.delete(item.previewUrl);
        }
      });
      return [];
    });
  }, []);

  const handleUploadResumeImages = useCallback(
    async (rawFiles: File[]) => {
      if (recognizing || sending || rawFiles.length === 0) return;
      const remaining = Math.max(0, MAX_BATCH_IMAGES - recognizedItems.length);
      if (remaining === 0) {
        setModalError(`单批最多添加 ${MAX_BATCH_IMAGES} 张简历图片，请先发送或移除部分记录`);
        return;
      }
      const files = rawFiles.slice(0, remaining);
      setModalError(
        rawFiles.length > remaining
          ? `已截取前 ${remaining} 张图片（单批上限 ${MAX_BATCH_IMAGES} 张）`
          : null,
      );
      setRecognizing(true);

      try {
        for (let offset = 0; offset < files.length; offset += UPLOAD_CHUNK_SIZE) {
          const chunk = files.slice(offset, offset + UPLOAD_CHUNK_SIZE);
          const endIdx = Math.min(files.length, offset + chunk.length);
          setRecognizeProgress(
            files.length > UPLOAD_CHUNK_SIZE
              ? `AI 正在自动识别第 ${offset + 1}–${endIdx} / ${files.length} 张简历图片…`
              : `AI 正在自动识别 ${files.length} 张简历图片中的姓名、邮箱与地址…`,
          );

          const res = await invitationApi.parseResumeImages(chunk);
          const drafts: RecognizedCandidateDraft[] = res.items.map((extracted, i) => {
            const sourceFile = chunk[i];
            let previewUrl: string | null = null;
            if (sourceFile && sourceFile.type.startsWith('image/')) {
              previewUrl = URL.createObjectURL(sourceFile);
              previewUrlsRef.current.add(previewUrl);
            }
            const emailStr = extracted.email ?? '';
            const valid = isDraftEmailValid(emailStr);
            return {
              id: `${Date.now()}-${offset + i}-${Math.random().toString(36).slice(2, 8)}`,
              filename: extracted.filename || sourceFile?.name || `简历图片-${offset + i + 1}`,
              previewUrl,
              selected: valid,
              candidateName: extracted.candidateName ?? '',
              email: emailStr,
              location: extracted.location ?? '',
              phone: extracted.phone ?? '',
              recognized: extracted.recognized,
              serverError: extracted.error,
            };
          });
          setRecognizedItems(prev => [...prev, ...drafts]);
        }
      } catch (err) {
        setModalError(getErrorMessage(err));
      } finally {
        setRecognizing(false);
        setRecognizeProgress(null);
      }
    },
    [recognizedItems.length, recognizing, sending],
  );

  // 支持在图片识别标签页直接 Ctrl+V / Cmd+V 粘贴剪贴板中的简历截图
  useEffect(() => {
    if (!modalOpen || inviteMode !== 'image') return;
    const onPaste = (e: ClipboardEvent) => {
      if (recognizing || sending) return;
      const imageFiles = Array.from(e.clipboardData?.files ?? []).filter(f =>
        f.type.startsWith('image/'),
      );
      if (imageFiles.length > 0) {
        e.preventDefault();
        void handleUploadResumeImages(imageFiles);
      }
    };
    window.addEventListener('paste', onPaste);
    return () => window.removeEventListener('paste', onPaste);
  }, [modalOpen, inviteMode, recognizing, sending, handleUploadResumeImages]);

  const handleUpdateRecognizedItem = useCallback(
    (
      id: string,
      patch: Partial<Pick<RecognizedCandidateDraft, 'selected' | 'candidateName' | 'email' | 'location'>>,
    ) => {
      setRecognizedItems(prev =>
        prev.map(item => (item.id === id ? { ...item, ...patch } : item)),
      );
    },
    [],
  );

  const handleRemoveRecognizedItem = useCallback((id: string) => {
    setRecognizedItems(prev => {
      const target = prev.find(it => it.id === id);
      if (target?.previewUrl) {
        URL.revokeObjectURL(target.previewUrl);
        previewUrlsRef.current.delete(target.previewUrl);
      }
      return prev.filter(it => it.id !== id);
    });
  }, []);

  const handleToggleSelectAllValid = useCallback((checked: boolean) => {
    setRecognizedItems(prev =>
      prev.map(item =>
        isDraftEmailValid(item.email) ? { ...item, selected: checked } : item,
      ),
    );
  }, []);

  const openSendModal = (mode: InviteModalMode = 'manual') => {
    setModalError(null);
    setInviteMode(mode);
    if (jobId && openJobs.some(j => String(j.id) === jobId)) {
      setInviteJobId(jobId);
    } else if (!inviteJobId && openJobs[0]) {
      setInviteJobId(String(openJobs[0].id));
    }
    setModalOpen(true);
  };

  const handleSendInvitations = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!inviteJobId) {
      setModalError('请先选择招聘中的岗位');
      return;
    }
    if (inviteMode === 'manual') {
      if (parsedEmailTokens.length === 0) {
        setModalError('请至少填写一个候选人邮箱');
        return;
      }
    } else {
      if (recognizedItems.length === 0) {
        setModalError('请先上传简历图片进行自动识别');
        return;
      }
      if (selectedValidRecipients.length === 0) {
        setModalError('请至少勾选一位具有正确邮箱地址的候选人（缺失邮箱可在表格中手动补填）');
        return;
      }
    }

    setSending(true);
    setModalError(null);
    try {
      const res = await invitationApi.send(
        inviteMode === 'manual'
          ? {
              jobId: Number(inviteJobId),
              emailsText,
              candidateName:
                parsedEmailTokens.length === 1 && candidateName.trim()
                  ? candidateName.trim()
                  : undefined,
              expireDays,
              skipScreening,
              note: note.trim() || undefined,
            }
          : {
              jobId: Number(inviteJobId),
              recipients: selectedValidRecipients,
              expireDays,
              skipScreening,
              note: note.trim() || undefined,
            },
      );
      setModalOpen(false);
      if (inviteMode === 'manual') {
        setEmailsText('');
        setCandidateName('');
      } else {
        clearRecognizedItems();
      }
      setNote('');
      const parts: string[] = [];
      if (res.createdCount > 0) parts.push(`新增邀请 ${res.createdCount} 人`);
      if (res.resentCount > 0) parts.push(`重发更新 ${res.resentCount} 人`);
      if (res.failed.length > 0) {
        parts.push(`格式无效忽略 ${res.failed.length} 条（${res.failed.map(f => f.email).join(', ')}）`);
      }
      showNotice(parts.join('，') || '面试邀请已发送');
      if (!res.mailEnabled) {
        setMailWarning('当前系统尚未启用外部发信通道，邀请权限已开通但邮件处于待发状态，请管理员前往「邮件设置」启用 SMTP 或企业微信发信。');
      } else {
        setMailWarning(null);
      }
      setPage(1);
      await loadData();
    } catch (err) {
      setModalError(getErrorMessage(err));
    } finally {
      setSending(false);
    }
  };

  const handleResend = async (item: InvitationListItem) => {
    setActingId(item.id);
    setError(null);
    try {
      await invitationApi.resend(item.id);
      showNotice(`已重新向 ${item.email} 投递面试邀请邮件`);
      await loadData();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setActingId(null);
    }
  };

  const handleConfirmRevoke = async () => {
    if (!revokeTarget) return;
    setActingId(revokeTarget.id);
    setError(null);
    try {
      await invitationApi.revoke(revokeTarget.id);
      showNotice(`已撤销 ${revokeTarget.email} 的面试邀请`);
      setRevokeTarget(null);
      await loadData();
    } catch (err) {
      setRevokeTarget(null);
      setError(getErrorMessage(err));
    } finally {
      setActingId(null);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="mx-auto max-w-6xl space-y-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">面试邀请管理</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            支持直接填写邮箱或批量上传简历图片自动识别姓名与邮箱发送邀请，实时查看候选人是否参加面试（非受邀邮箱不允许投递简历与进入面试）
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="button"
            onClick={() => void loadData()}
            className="btn-secondary flex items-center gap-1.5 rounded-xl px-4 py-2 text-sm"
          >
            <RefreshCw className="h-4 w-4" />
            刷新状态
          </button>
          {canWrite && (
            <>
              <button
                type="button"
                data-testid="open-image-invite-btn"
                onClick={() => openSendModal('image')}
                className="btn-secondary flex items-center gap-2 rounded-xl border-primary-200 bg-primary-50/70 px-4 py-2 text-sm font-medium text-primary-700 hover:bg-primary-100 dark:border-primary-800 dark:bg-primary-900/30 dark:text-primary-300 dark:hover:bg-primary-900/50"
              >
                <ImagePlus className="h-4 w-4" />
                批量传图识别邀请
              </button>
              <button
                type="button"
                data-testid="open-send-invite-btn"
                onClick={() => openSendModal('manual')}
                className="btn-primary flex items-center gap-2 rounded-xl px-5 py-2 text-sm font-medium"
              >
                <Send className="h-4 w-4" />
                发送面试邀请
              </button>
            </>
          )}
        </div>
      </div>

      {/* 统计概览卡片 */}
      <div data-testid="invitation-stats" className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <div className="dark-card p-4">
          <div className="text-xs font-medium text-slate-500 dark:text-slate-400">总邀请人数</div>
          <div className="mt-1.5 text-2xl font-bold text-slate-900 dark:text-white">{stats?.total ?? 0}</div>
          <div className="mt-1 text-xs text-slate-400">仅受邀邮箱可验证投递</div>
        </div>
        <div className="dark-card p-4">
          <div className="text-xs font-medium text-emerald-600 dark:text-emerald-400">已参加面试</div>
          <div
            data-testid="stat-participated"
            className="mt-1.5 text-2xl font-bold text-emerald-600 dark:text-emerald-400"
          >
            {stats?.participated ?? 0}
          </div>
          <div className="mt-1 text-xs text-slate-400">已进入笔试/面试或已交卷</div>
        </div>
        <div className="dark-card p-4">
          <div className="text-xs font-medium text-indigo-600 dark:text-indigo-400">已投递 · 待参加面试</div>
          <div className="mt-1.5 text-2xl font-bold text-indigo-600 dark:text-indigo-400">
            {stats?.pendingInterview ?? 0}
          </div>
          <div className="mt-1 text-xs text-slate-400">含 AI 初筛中与待开考</div>
        </div>
        <div className="dark-card p-4">
          <div className="text-xs font-medium text-amber-600 dark:text-amber-400">尚未投递 / 未参加</div>
          <div
            data-testid="stat-not-applied"
            className="mt-1.5 text-2xl font-bold text-amber-600 dark:text-amber-400"
          >
            {stats?.notApplied ?? 0}
          </div>
          <div className="mt-1 text-xs text-slate-400">
            初筛未过 {stats?.screenRejected ?? 0} · 过期/撤销 {stats?.expiredOrRevoked ?? 0}
          </div>
        </div>
      </div>

      {notice && (
        <div className="flex items-center gap-2 rounded-xl bg-emerald-50 px-4 py-2.5 text-sm text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300">
          <CheckCircle2 className="h-4 w-4 shrink-0" />
          {notice}
        </div>
      )}

      {mailWarning && (
        <div className="flex flex-wrap items-center justify-between gap-2 rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5 text-xs text-amber-800 dark:border-amber-800/60 dark:bg-amber-900/20 dark:text-amber-200">
          <span className="flex items-center gap-2">
            <AlertTriangle className="h-4 w-4 shrink-0" />
            {mailWarning}
          </span>
          {isAdmin && (
            <Link to={ROUTES.adminMail} className="font-semibold underline">
              前往配置邮件服务
            </Link>
          )}
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
          {FILTER_TABS.map(t => (
            <button
              key={t.key}
              type="button"
              data-testid={`invite-filter-${t.key}`}
              onClick={() => {
                setTab(t.key);
                setPage(1);
              }}
              className={`rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
                tab === t.key
                  ? 'bg-primary-500 text-white'
                  : 'bg-slate-100 text-slate-600 hover:bg-slate-200 dark:bg-slate-800 dark:text-slate-300 dark:hover:bg-slate-700'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <select
            value={jobId}
            onChange={e => handleJobFilterChange(e.target.value)}
            className="rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700 dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
          >
            <option value="">全部岗位</option>
            {jobs.map(j => (
              <option key={j.id} value={j.id}>
                {j.title}
              </option>
            ))}
          </select>

          <form
            onSubmit={e => {
              e.preventDefault();
              setKeyword(keywordInput.trim());
              setPage(1);
            }}
            className="flex flex-1 items-center gap-2"
          >
            <div className="relative min-w-[220px] flex-1">
              <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
              <input
                value={keywordInput}
                onChange={e => setKeywordInput(e.target.value)}
                placeholder="搜索候选人邮箱或姓名…"
                className="w-full rounded-xl border border-slate-200 bg-white py-2 pl-9 pr-3 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-white"
              />
            </div>
            <button type="submit" className="btn-secondary rounded-xl px-4 py-2 text-sm">
              搜索
            </button>
          </form>
        </div>
      </div>

      {/* 列表表格 */}
      <div className="dark-card overflow-hidden" data-testid="invitation-table">
        {loading ? (
          <div className="flex justify-center py-20">
            <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
          </div>
        ) : items.length === 0 ? (
          <div className="flex flex-col items-center gap-3 py-16 text-center">
            <Mail className="h-10 w-10 text-slate-300 dark:text-slate-600" />
            <p className="text-sm text-slate-500 dark:text-slate-400">暂无匹配的面试邀请记录</p>
            {canWrite && (
              <div className="flex flex-wrap justify-center gap-2">
                <button
                  type="button"
                  onClick={() => openSendModal('manual')}
                  className="btn-primary rounded-xl px-5 py-2 text-sm"
                >
                  发送第一封面试邀请
                </button>
                <button
                  type="button"
                  onClick={() => openSendModal('image')}
                  className="btn-secondary flex items-center gap-1.5 rounded-xl px-4 py-2 text-sm"
                >
                  <ImagePlus className="h-4 w-4" />
                  批量上传简历图片识别邀请
                </button>
              </div>
            )}
          </div>
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="border-b border-slate-200 bg-slate-50/70 text-xs font-semibold text-slate-500 dark:border-slate-700 dark:bg-slate-800/60 dark:text-slate-400">
                <tr>
                  <th className="px-4 py-3">受邀邮箱 / 候选人</th>
                  <th className="px-4 py-3">邀请岗位</th>
                  <th className="px-4 py-3">是否参加面试</th>
                  <th className="px-4 py-3">邮件投递状态</th>
                  <th className="px-4 py-3">邀请时间 / 有效期</th>
                  <th className="px-4 py-3 text-right">操作</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                {items.map(item => (
                  <tr
                    key={item.id}
                    data-testid={`invitation-row-${item.id}`}
                    className="transition-colors hover:bg-slate-50/60 dark:hover:bg-slate-800/40"
                  >
                    <td className="px-4 py-3.5 align-top">
                      <div className="font-medium text-slate-900 dark:text-white">{item.email}</div>
                      <div className="mt-0.5 flex flex-wrap items-center gap-1.5 text-xs text-slate-500 dark:text-slate-400">
                        {item.candidateName && <span>{item.candidateName}</span>}
                        {item.skipScreening && (
                          <span className="rounded bg-indigo-50 px-1.5 py-0.5 text-[11px] font-medium text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300">
                            免初筛
                          </span>
                        )}
                      </div>
                      {item.note && (
                        <div className="mt-1 max-w-xs truncate text-xs text-slate-400" title={item.note}>
                          备注：{item.note}
                        </div>
                      )}
                    </td>

                    <td className="px-4 py-3.5 align-top text-slate-700 dark:text-slate-200">
                      {item.jobTitle}
                    </td>

                    <td
                      className="px-4 py-3.5 align-top"
                      data-testid={`invite-participation-${item.id}`}
                    >
                      <div className="flex flex-wrap items-center gap-1.5">
                        {item.participated ? (
                          <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2 py-0.5 text-xs font-semibold text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200">
                            <UserCheck className="h-3.5 w-3.5" />
                            已参加面试
                          </span>
                        ) : (
                          <span className="inline-flex items-center gap-1 rounded-full bg-slate-100 px-2 py-0.5 text-xs font-semibold text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                            <UserX className="h-3.5 w-3.5" />
                            未参加面试
                          </span>
                        )}
                        <span
                          className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
                            INVITATION_PARTICIPATION_BADGES[item.participationStatus]
                          }`}
                        >
                          {INVITATION_PARTICIPATION_LABELS[item.participationStatus]}
                        </span>
                      </div>
                      <div className="mt-1 text-xs text-slate-500 dark:text-slate-400">
                        {item.participationDetail}
                      </div>
                      {(item.screeningScore != null || item.writtenScore != null || item.aiScore != null) && (
                        <div className="mt-1 flex flex-wrap gap-2 text-xs text-slate-500 dark:text-slate-400">
                          {item.screeningScore != null && <span>初筛 {item.screeningScore} 分</span>}
                          {item.writtenScore != null && <span>笔试 {item.writtenScore} 分</span>}
                          {item.aiScore != null && (
                            <span className="font-medium text-primary-600 dark:text-primary-400">
                              综合 {item.aiScore} 分
                            </span>
                          )}
                        </div>
                      )}
                    </td>

                    <td className="px-4 py-3.5 align-top">
                      <div className="flex flex-wrap items-center gap-1.5">
                        {item.emailStatus ? (
                          <span
                            className={`rounded-full px-2.5 py-0.5 text-xs font-medium ${
                              EMAIL_STATUS_BADGES[item.emailStatus]
                            }`}
                          >
                            {EMAIL_LOG_STATUS_LABELS[item.emailStatus]}
                          </span>
                        ) : (
                          <span className="rounded-full bg-slate-100 px-2.5 py-0.5 text-xs text-slate-500 dark:bg-slate-800 dark:text-slate-400">
                            待发信
                          </span>
                        )}
                        <span className="text-xs text-slate-400">×{item.sendCount}</span>
                      </div>
                      {item.emailSentAt && (
                        <div className="mt-1 text-xs text-slate-400">
                          送达于 {formatDateTime(item.emailSentAt)}
                        </div>
                      )}
                      {item.emailStatus === 'FAILED' && item.emailError && (
                        <div className="mt-1 max-w-[200px] truncate text-xs text-rose-500" title={item.emailError}>
                          {item.emailError}
                        </div>
                      )}
                    </td>

                    <td className="px-4 py-3.5 align-top text-xs text-slate-500 dark:text-slate-400">
                      <div>邀请：{formatDateTime(item.invitedAt)}</div>
                      {item.expiresAt && <div className="mt-0.5">截止：{formatDateTime(item.expiresAt)}</div>}
                      {item.invitedByName && <div className="mt-0.5 text-slate-400">邀请人：{item.invitedByName}</div>}
                    </td>

                    <td className="px-4 py-3.5 text-right align-top">
                      <div className="flex flex-wrap justify-end gap-2">
                        {item.applicationId && (
                          <Link
                            to={ROUTES.adminApplicationDetail(item.applicationId)}
                            data-testid={`invite-view-app-${item.id}`}
                            className="inline-flex items-center gap-1 rounded-lg bg-primary-50 px-2.5 py-1 text-xs font-medium text-primary-700 hover:bg-primary-100 dark:bg-primary-900/30 dark:text-primary-300"
                          >
                            查看投递审查
                            <ExternalLink className="h-3 w-3" />
                          </Link>
                        )}
                        {canWrite && (
                          <>
                            <button
                              type="button"
                              data-testid={`invite-resend-${item.id}`}
                              disabled={actingId === item.id}
                              onClick={() => void handleResend(item)}
                              className="inline-flex items-center gap-1 rounded-lg border border-slate-200 px-2.5 py-1 text-xs text-slate-600 hover:bg-slate-100 disabled:opacity-50 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                            >
                              {actingId === item.id ? (
                                <Loader2 className="h-3 w-3 animate-spin" />
                              ) : (
                                <RotateCw className="h-3 w-3" />
                              )}
                              重发邮件
                            </button>
                            {item.status === 'ACTIVE' && (
                              <button
                                type="button"
                                data-testid={`invite-revoke-${item.id}`}
                                disabled={actingId === item.id}
                                onClick={() => setRevokeTarget(item)}
                                className="inline-flex items-center gap-1 rounded-lg px-2.5 py-1 text-xs text-rose-600 hover:bg-rose-50 disabled:opacity-50 dark:text-rose-400 dark:hover:bg-rose-900/20"
                              >
                                <XCircle className="h-3 w-3" />
                                撤销
                              </button>
                            )}
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {/* 分页 */}
        {total > PAGE_SIZE && (
          <div className="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-500 dark:border-slate-800 dark:text-slate-400">
            <span>
              共 {total} 条邀请 · 第 {page} / {totalPages} 页
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

      {/* 发送面试邀请弹窗 */}
      {modalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4 backdrop-blur-sm">
          <div
            data-testid="send-invite-modal"
            className={`max-h-[92vh] w-full overflow-y-auto rounded-2xl bg-white p-6 shadow-2xl transition-all dark:bg-slate-800 ${
              inviteMode === 'image' ? 'max-w-4xl' : 'max-w-lg'
            }`}
          >
            <div className="flex items-center justify-between gap-4">
              <div>
                <h2 className="text-lg font-bold text-slate-900 dark:text-white">发送面试邀请邮件</h2>
                <p className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                  支持手动输入邮箱或批量上传简历图片自动识别姓名、邮箱地址与所在地后一键发送邀请邮件
                </p>
              </div>
              <button
                type="button"
                disabled={sending || recognizing}
                onClick={() => setModalOpen(false)}
                className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 hover:text-slate-600 disabled:opacity-40 dark:hover:bg-slate-700"
              >
                <X className="h-5 w-5" />
              </button>
            </div>

            {openJobs.length === 0 ? (
              <div className="mt-6 space-y-4 rounded-xl bg-amber-50 p-4 text-sm text-amber-800 dark:bg-amber-900/20 dark:text-amber-200">
                <p>当前暂无处于「招聘中」状态的开放岗位，请先发布岗位后再发送面试邀请。</p>
                <div className="flex justify-end">
                  <Link to={ROUTES.adminJobs} className="btn-primary rounded-xl px-4 py-2 text-xs">
                    前往岗位管理
                  </Link>
                </div>
              </div>
            ) : (
              <form onSubmit={handleSendInvitations} className="mt-4 space-y-4">
                {/* 邀请方式切换标签页 */}
                <div className="grid grid-cols-2 gap-1.5 rounded-xl bg-slate-100 p-1 dark:bg-slate-900/80">
                  <button
                    type="button"
                    data-testid="invite-tab-manual"
                    disabled={sending || recognizing}
                    onClick={() => {
                      setInviteMode('manual');
                      setModalError(null);
                    }}
                    className={`flex items-center justify-center gap-1.5 rounded-lg py-2 text-xs font-semibold transition-all ${
                      inviteMode === 'manual'
                        ? 'bg-white text-primary-600 shadow-sm dark:bg-slate-800 dark:text-primary-400'
                        : 'text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-200'
                    }`}
                  >
                    <Mail className="h-3.5 w-3.5" />
                    手动填写邮箱
                  </button>
                  <button
                    type="button"
                    data-testid="invite-tab-image"
                    disabled={sending || recognizing}
                    onClick={() => {
                      setInviteMode('image');
                      setModalError(null);
                    }}
                    className={`flex items-center justify-center gap-1.5 rounded-lg py-2 text-xs font-semibold transition-all ${
                      inviteMode === 'image'
                        ? 'bg-white text-primary-600 shadow-sm dark:bg-slate-800 dark:text-primary-400'
                        : 'text-slate-600 hover:text-slate-900 dark:text-slate-400 dark:hover:text-slate-200'
                    }`}
                  >
                    <ImagePlus className="h-3.5 w-3.5" />
                    批量上传简历图片识别
                    {recognizedItems.length > 0 && (
                      <span className="rounded-full bg-primary-100 px-1.5 py-0.2 text-[10px] font-bold text-primary-700 dark:bg-primary-900/50 dark:text-primary-300">
                        {selectedValidRecipients.length}/{recognizedItems.length}
                      </span>
                    )}
                  </button>
                </div>

                {modalError && (
                  <div className="rounded-xl bg-red-50 px-3.5 py-2.5 text-xs text-red-600 dark:bg-red-900/20 dark:text-red-400">
                    {modalError}
                  </div>
                )}

                <div className="grid gap-3 sm:grid-cols-3">
                  <div className="sm:col-span-2">
                    <label className="mb-1 block text-xs font-medium text-slate-700 dark:text-slate-300">
                      邀请应聘岗位 *
                    </label>
                    <select
                      data-testid="invite-job-select"
                      required
                      value={inviteJobId}
                      onChange={e => setInviteJobId(e.target.value)}
                      className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                    >
                      {openJobs.map(j => (
                        <option key={j.id} value={j.id}>
                          {j.title}
                        </option>
                      ))}
                    </select>
                  </div>
                  <div>
                    <label className="mb-1 block text-xs font-medium text-slate-700 dark:text-slate-300">
                      邀请有效期（天）
                    </label>
                    <input
                      data-testid="invite-expire-days"
                      type="number"
                      min={1}
                      max={90}
                      value={expireDays}
                      onChange={e => setExpireDays(Math.max(1, Math.min(90, Number(e.target.value) || 7)))}
                      className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                    />
                  </div>
                </div>

                {inviteMode === 'manual' ? (
                  <>
                    <div>
                      <div className="mb-1 flex items-center justify-between">
                        <label className="text-xs font-medium text-slate-700 dark:text-slate-300">
                          面试者邮箱 *（支持批量填写，每行一个或用逗号分隔）
                        </label>
                        {parsedEmailTokens.length > 0 && (
                          <span className="text-xs font-medium text-primary-600 dark:text-primary-400">
                            已识别 {parsedEmailTokens.length} 个邮箱
                          </span>
                        )}
                      </div>
                      <textarea
                        data-testid="invite-emails-input"
                        required
                        rows={4}
                        value={emailsText}
                        onChange={e => setEmailsText(e.target.value)}
                        placeholder={'candidate1@example.com\ncandidate2@example.com'}
                        className="w-full rounded-xl border border-slate-200 bg-white p-3 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                      />
                    </div>

                    <div>
                      <label className="mb-1 block text-xs font-medium text-slate-700 dark:text-slate-300">
                        候选人姓名（单人邀请选填）
                      </label>
                      <input
                        data-testid="invite-candidate-name"
                        maxLength={64}
                        disabled={parsedEmailTokens.length > 1}
                        value={candidateName}
                        onChange={e => setCandidateName(e.target.value)}
                        placeholder={parsedEmailTokens.length > 1 ? '批量邀请时自动使用邮箱称呼' : '用于邮件称呼，如：张同学'}
                        className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:border-primary-500 focus:outline-none disabled:opacity-50 dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                      />
                    </div>
                  </>
                ) : (
                  <BatchResumeImageInvitePanel
                    items={recognizedItems}
                    recognizing={recognizing}
                    recognizeProgress={recognizeProgress}
                    maxImages={MAX_BATCH_IMAGES}
                    disabled={sending}
                    onUploadFiles={files => void handleUploadResumeImages(files)}
                    onUpdateItem={handleUpdateRecognizedItem}
                    onRemoveItem={handleRemoveRecognizedItem}
                    onClearAll={clearRecognizedItems}
                    onToggleSelectAllValid={handleToggleSelectAllValid}
                  />
                )}

                <label className="flex items-start gap-2 rounded-xl bg-slate-50 p-3 text-xs text-slate-700 dark:bg-slate-900/60 dark:text-slate-300">
                  <input
                    data-testid="invite-skip-screening"
                    type="checkbox"
                    checked={skipScreening}
                    onChange={e => setSkipScreening(e.target.checked)}
                    className="mt-0.5 h-4 w-4 accent-primary-500"
                  />
                  <span>
                    <strong>上传简历后免 AI 初筛，直通在线面试</strong>
                    （适合 HR 已人工筛选过简历的候选人；仍保留简历上传以便 AI 针对简历内容出题与核验真实性）
                  </span>
                </label>

                <div>
                  <label className="mb-1 block text-xs font-medium text-slate-700 dark:text-slate-300">
                    邮件补充说明（选填，将随邀请邮件发送给候选人）
                  </label>
                  <input
                    data-testid="invite-note"
                    maxLength={500}
                    value={note}
                    onChange={e => setNote(e.target.value)}
                    placeholder="例如：请在周五 18:00 前使用受邀邮箱完成在线笔试与面试"
                    className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-900 dark:text-white"
                  />
                </div>

                <div className="flex justify-end gap-3 pt-2">
                  <button
                    type="button"
                    disabled={sending || recognizing}
                    onClick={() => setModalOpen(false)}
                    className="btn-secondary rounded-xl px-4 py-2 text-sm"
                  >
                    取消
                  </button>
                  {inviteMode === 'manual' ? (
                    <button
                      type="submit"
                      data-testid="confirm-send-invite-btn"
                      disabled={sending}
                      className="btn-primary flex items-center gap-2 rounded-xl px-5 py-2 text-sm font-medium disabled:opacity-60"
                    >
                      {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                      {sending ? '正在发送邀请…' : '发送邀请邮件'}
                    </button>
                  ) : (
                    <button
                      type="submit"
                      data-testid="confirm-send-image-invite-btn"
                      disabled={sending || recognizing || selectedValidRecipients.length === 0}
                      className="btn-primary flex items-center gap-2 rounded-xl px-5 py-2 text-sm font-medium disabled:opacity-60"
                    >
                      {sending ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
                      {sending
                        ? '正在发送邀请…'
                        : selectedValidRecipients.length > 0
                          ? `向已选 ${selectedValidRecipients.length} 位候选人发送邀请`
                          : '请先上传识别并勾选候选人'}
                    </button>
                  )}
                </div>
              </form>
            )}
          </div>
        </div>
      )}

      <ConfirmDialog
        open={revokeTarget !== null}
        title="确认撤销面试邀请"
        message={
          revokeTarget
            ? `撤销后，邮箱「${revokeTarget.email}」将无法继续投递「${revokeTarget.jobTitle}」或进入未完成的在线面试。确认撤销吗？`
            : ''
        }
        confirmText="确认撤销"
        confirmVariant="danger"
        confirmTestId="confirm-revoke-invite-btn"
        loading={revokeTarget !== null && actingId === revokeTarget.id}
        onConfirm={() => void handleConfirmRevoke()}
        onCancel={() => setRevokeTarget(null)}
      />
    </div>
  );
}
