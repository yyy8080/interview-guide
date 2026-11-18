import { useCallback, useEffect, useState } from 'react';
import { CheckCircle2, KeyRound, Loader2, Plus, ShieldCheck, UserCog, X } from 'lucide-react';
import { adminUserApi, authApi } from '../../api/auth';
import { getErrorMessage } from '../../api/request';
import { getStoredUser, saveAuth } from '../../utils/authStorage';
import { formatDateTime } from '../../utils/date';
import { ADMIN_ROLE_LABELS, type AdminRole, type AdminUser } from '../../types/auth';

const MIN_PASSWORD_LENGTH = 8;
const inputClass = 'dark-input w-full rounded-lg px-3 py-2 text-sm outline-none';
const labelClass = 'mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300';

type DialogState =
  | { type: 'create' }
  | { type: 'edit'; user: AdminUser }
  | { type: 'reset'; user: AdminUser };

function ChangePasswordCard() {
  const [oldPassword, setOldPassword] = useState('');
  const [newPassword, setNewPassword] = useState('');
  const [confirm, setConfirm] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setDone(false);
    if (newPassword.length < MIN_PASSWORD_LENGTH) {
      setError(`新密码至少 ${MIN_PASSWORD_LENGTH} 位`);
      return;
    }
    if (newPassword !== confirm) {
      setError('两次输入的新密码不一致');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const response = await authApi.changePassword({ oldPassword, newPassword });
      saveAuth(response);
      setOldPassword('');
      setNewPassword('');
      setConfirm('');
      setDone(true);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="dark-card space-y-4 p-6">
      <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-white">
        <KeyRound className="h-5 w-5 text-primary-500" />修改密码
      </h2>
      <p className="text-sm text-slate-500 dark:text-slate-400">修改后其他设备上的登录将失效</p>
      {error && <div className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">{error}</div>}
      {done && (
        <div className="flex items-center gap-2 rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300">
          <CheckCircle2 className="h-4 w-4" />密码已修改
        </div>
      )}
      <div className="grid gap-4 md:grid-cols-3">
        <div>
          <label className={labelClass}>当前密码</label>
          <input className={inputClass} type="password" autoComplete="current-password" value={oldPassword} onChange={e => setOldPassword(e.target.value)} required />
        </div>
        <div>
          <label className={labelClass}>新密码</label>
          <input className={inputClass} type="password" autoComplete="new-password" value={newPassword} onChange={e => setNewPassword(e.target.value)} required />
        </div>
        <div>
          <label className={labelClass}>确认新密码</label>
          <input className={inputClass} type="password" autoComplete="new-password" value={confirm} onChange={e => setConfirm(e.target.value)} required />
        </div>
      </div>
      <div className="flex justify-end">
        <button type="submit" disabled={saving} className="btn-primary flex items-center gap-2 rounded-xl px-5 py-2 text-sm disabled:opacity-60">
          {saving && <Loader2 className="h-4 w-4 animate-spin" />}保存
        </button>
      </div>
    </form>
  );
}

interface UserDialogProps {
  state: DialogState;
  onClose: () => void;
  onSaved: () => void;
}

function UserDialog({ state, onClose, onSaved }: UserDialogProps) {
  const editingUser = state.type === 'create' ? null : state.user;
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState(editingUser?.displayName ?? '');
  const [email, setEmail] = useState(editingUser?.email ?? '');
  const [role, setRole] = useState<AdminRole>(editingUser?.role ?? 'HR');
  const [enabled, setEnabled] = useState(editingUser?.enabled ?? true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const title = state.type === 'create' ? '新建账号' : state.type === 'edit' ? '编辑账号' : '重置密码';

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if ((state.type === 'create' || state.type === 'reset') && password.length < MIN_PASSWORD_LENGTH) {
      setError(`密码至少 ${MIN_PASSWORD_LENGTH} 位`);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      if (state.type === 'create') {
        await adminUserApi.create({
          username: username.trim(),
          password,
          displayName: displayName.trim() || undefined,
          email: email.trim() || undefined,
          role,
        });
      } else if (state.type === 'edit') {
        await adminUserApi.update(state.user.id, {
          displayName: displayName.trim() || undefined,
          email: email.trim() || undefined,
          role,
          enabled,
        });
      } else {
        await adminUserApi.resetPassword(state.user.id, password);
      }
      onSaved();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-slate-900/50 p-4">
      <form onSubmit={handleSubmit} className="w-full max-w-md space-y-4 rounded-2xl bg-white p-6 shadow-xl dark:bg-slate-800">
        <div className="flex items-center justify-between">
          <h3 className="text-lg font-semibold text-slate-900 dark:text-white">{title}</h3>
          <button type="button" onClick={onClose} className="rounded-lg p-1 text-slate-400 hover:bg-slate-100 dark:hover:bg-slate-700">
            <X className="h-5 w-5" />
          </button>
        </div>
        {error && <div className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">{error}</div>}

        {state.type === 'create' && (
          <div>
            <label className={labelClass}>用户名</label>
            <input className={inputClass} value={username} onChange={e => setUsername(e.target.value)} autoComplete="off" required />
          </div>
        )}
        {state.type !== 'create' && (
          <p className="text-sm text-slate-500 dark:text-slate-400">账号：{state.user.username}</p>
        )}
        {(state.type === 'create' || state.type === 'reset') && (
          <div>
            <label className={labelClass}>{state.type === 'reset' ? '新密码' : '初始密码'}</label>
            <input className={inputClass} type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete="new-password" required />
          </div>
        )}
        {state.type !== 'reset' && (
          <>
            <div>
              <label className={labelClass}>姓名</label>
              <input className={inputClass} value={displayName} onChange={e => setDisplayName(e.target.value)} />
            </div>
            <div>
              <label className={labelClass}>邮箱</label>
              <input className={inputClass} type="email" value={email} onChange={e => setEmail(e.target.value)} />
            </div>
            <div>
              <label className={labelClass}>角色</label>
              <select className={inputClass} value={role} onChange={e => setRole(e.target.value as AdminRole)}>
                {Object.entries(ADMIN_ROLE_LABELS).map(([value, label]) => (
                  <option key={value} value={value}>{label}</option>
                ))}
              </select>
            </div>
            {state.type === 'edit' && (
              <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
                <input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} className="h-4 w-4 accent-primary-500" />
                启用账号
              </label>
            )}
          </>
        )}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" onClick={onClose} className="btn-secondary rounded-xl px-4 py-2 text-sm">取消</button>
          <button type="submit" disabled={saving} className="btn-primary flex items-center gap-2 rounded-xl px-4 py-2 text-sm disabled:opacity-60">
            {saving && <Loader2 className="h-4 w-4 animate-spin" />}确定
          </button>
        </div>
      </form>
    </div>
  );
}

function UserManagementCard() {
  const currentUser = getStoredUser();
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [dialog, setDialog] = useState<DialogState | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setUsers(await adminUserApi.list());
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  return (
    <section className="dark-card space-y-4 p-6">
      <div className="flex items-center justify-between">
        <h2 className="flex items-center gap-2 text-base font-semibold text-slate-900 dark:text-white">
          <UserCog className="h-5 w-5 text-primary-500" />后台账号
        </h2>
        <button onClick={() => setDialog({ type: 'create' })} className="btn-primary flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm">
          <Plus className="h-4 w-4" />新建账号
        </button>
      </div>
      {error && <div className="rounded-lg bg-red-50 px-3 py-2 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">{error}</div>}
      {loading ? (
        <div className="flex justify-center py-8"><Loader2 className="h-6 w-6 animate-spin text-primary-500" /></div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="border-b border-slate-100 text-slate-500 dark:border-slate-700 dark:text-slate-400">
              <tr>
                <th className="py-2 pr-4 font-medium">账号</th>
                <th className="py-2 pr-4 font-medium">角色</th>
                <th className="py-2 pr-4 font-medium">状态</th>
                <th className="py-2 pr-4 font-medium">最近登录</th>
                <th className="py-2 font-medium text-right">操作</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100 dark:divide-slate-700">
              {users.map(user => (
                <tr key={user.id} className="text-slate-700 dark:text-slate-300">
                  <td className="py-3 pr-4">
                    <div className="font-medium text-slate-900 dark:text-white">
                      {user.displayName || user.username}
                      {user.id === currentUser?.id && <span className="ml-2 text-xs text-primary-500">（我）</span>}
                    </div>
                    <div className="text-xs text-slate-400">{user.username}{user.email ? ` · ${user.email}` : ''}</div>
                  </td>
                  <td className="py-3 pr-4">{ADMIN_ROLE_LABELS[user.role]}</td>
                  <td className="py-3 pr-4">
                    {user.enabled
                      ? <span className="text-emerald-600 dark:text-emerald-400">启用</span>
                      : <span className="text-slate-400">已停用</span>}
                  </td>
                  <td className="py-3 pr-4 text-slate-500">{user.lastLoginAt ? formatDateTime(user.lastLoginAt) : '-'}</td>
                  <td className="py-3 text-right">
                    <button onClick={() => setDialog({ type: 'edit', user })} className="rounded-lg px-2 py-1 text-primary-600 hover:bg-primary-50 dark:text-primary-400 dark:hover:bg-primary-900/30">编辑</button>
                    <button onClick={() => setDialog({ type: 'reset', user })} className="rounded-lg px-2 py-1 text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-700">重置密码</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {dialog && (
        <UserDialog
          key={dialog.type === 'create' ? 'create' : `${dialog.type}-${dialog.user.id}`}
          state={dialog}
          onClose={() => setDialog(null)}
          onSaved={() => {
            setDialog(null);
            load();
          }}
        />
      )}
    </section>
  );
}

export default function AccountPage() {
  const user = getStoredUser();
  const isAdmin = user?.role === 'ADMIN';

  return (
    <div className="mx-auto max-w-5xl space-y-6">
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white">账号管理</h1>
        {user && (
          <p className="mt-1 flex items-center gap-1.5 text-sm text-slate-500 dark:text-slate-400">
            <ShieldCheck className="h-4 w-4" />
            {user.displayName || user.username} · {ADMIN_ROLE_LABELS[user.role]}
          </p>
        )}
      </div>
      <ChangePasswordCard />
      {isAdmin && <UserManagementCard />}
    </div>
  );
}
