import { useCallback, useEffect, useState } from 'react';
import {
  Building2,
  CheckCircle2,
  ChevronLeft,
  ChevronRight,
  FileText,
  Info,
  Loader2,
  Mail,
  RefreshCw,
  RotateCcw,
  Save,
  Search,
  Send,
  Server,
  Settings2,
} from 'lucide-react';
import { mailApi } from '../../api/mail';
import { getErrorMessage } from '../../api/request';
import { formatDateTime } from '../../utils/date';
import {
  EMAIL_LOG_STATUS_LABELS,
  MAIL_SECURITY_LABELS,
  type EmailLogItem,
  type EmailLogStatus,
  type EmailTemplateItem,
  type MailChannel,
  type MailSecurity,
  type MailSettings,
  type WeComEmailAccount,
} from '../../types/recruitment';

type MailTab = 'smtp' | 'templates' | 'logs';

const COMMON_VARIABLES = ['companyName', 'contactInfo', 'portalUrl'];
const LOG_PAGE_SIZE = 20;

export default function MailSettingsPage() {
  const [tab, setTab] = useState<MailTab>('smtp');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // 发件通道配置表单（支持 SMTP 与企业微信邮件 API）
  const [settings, setSettings] = useState<MailSettings | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [channel, setChannel] = useState<MailChannel>('SMTP');
  const [host, setHost] = useState('');
  const [port, setPort] = useState('465');
  const [security, setSecurity] = useState<MailSecurity>('SSL');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [fromAddress, setFromAddress] = useState('');
  const [fromName, setFromName] = useState('');
  const [wecomCorpId, setWecomCorpId] = useState('');
  const [wecomSecret, setWecomSecret] = useState('');
  const [wecomApiBaseUrl, setWecomApiBaseUrl] = useState('');
  const [wecomAccount, setWecomAccount] = useState<WeComEmailAccount | null>(null);
  const [queryingWecomAccount, setQueryingWecomAccount] = useState(false);
  const [companyName, setCompanyName] = useState('');
  const [portalBaseUrl, setPortalBaseUrl] = useState('');
  const [contactInfo, setContactInfo] = useState('');
  const [savingSettings, setSavingSettings] = useState(false);

  const [testTo, setTestTo] = useState('');
  const [sendingTest, setSendingTest] = useState(false);

  // 模板
  const [templates, setTemplates] = useState<EmailTemplateItem[]>([]);
  const [selectedCode, setSelectedCode] = useState<string>('');
  const [tplSubject, setTplSubject] = useState('');
  const [tplBody, setTplBody] = useState('');
  const [tplEnabled, setTplEnabled] = useState(true);
  const [savingTpl, setSavingTpl] = useState(false);

  // 日志
  const [logStatus, setLogStatus] = useState<EmailLogStatus | ''>('');
  const [logPage, setLogPage] = useState(1);
  const [logs, setLogs] = useState<EmailLogItem[]>([]);
  const [logTotal, setLogTotal] = useState(0);
  const [logsLoading, setLogsLoading] = useState(false);
  const [resendingId, setResendingId] = useState<number | null>(null);

  const showNotice = (msg: string) => {
    setNotice(msg);
    window.setTimeout(() => setNotice(null), 3000);
  };

  const applySettings = (data: MailSettings) => {
    setSettings(data);
    setEnabled(data.enabled);
    setChannel(data.channel ?? 'SMTP');
    setHost(data.host ?? '');
    setPort(data.port != null ? String(data.port) : '465');
    setSecurity(data.security ?? 'SSL');
    setUsername(data.username ?? '');
    setPassword('');
    setFromAddress(data.fromAddress ?? '');
    setFromName(data.fromName ?? '');
    setWecomCorpId(data.wecomCorpId ?? '');
    setWecomSecret('');
    setWecomApiBaseUrl(data.wecomApiBaseUrl ?? '');
    setCompanyName(data.companyName ?? '');
    setPortalBaseUrl(data.portalBaseUrl ?? '');
    setContactInfo(data.contactInfo ?? '');
  };

  const selectTemplate = (tpl: EmailTemplateItem) => {
    setSelectedCode(tpl.code);
    setTplSubject(tpl.subject);
    setTplBody(tpl.body);
    setTplEnabled(tpl.enabled);
  };

  useEffect(() => {
    Promise.all([mailApi.getSettings(), mailApi.listTemplates()])
      .then(([s, tpls]) => {
        applySettings(s);
        setTemplates(tpls);
        if (tpls.length > 0) {
          selectTemplate(tpls[0]);
        }
      })
      .catch(err => setError(getErrorMessage(err)))
      .finally(() => setLoading(false));
  }, []);

  const loadLogs = useCallback(async () => {
    setLogsLoading(true);
    try {
      const res = await mailApi.listLogs({
        status: logStatus || undefined,
        page: logPage,
        size: LOG_PAGE_SIZE,
      });
      setLogs(res.items);
      setLogTotal(res.total);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setLogsLoading(false);
    }
  }, [logStatus, logPage]);

  useEffect(() => {
    if (tab === 'logs') {
      loadLogs();
    }
  }, [tab, loadLogs]);

  const handleSaveSettings = async (e: React.FormEvent) => {
    e.preventDefault();
    setSavingSettings(true);
    setError(null);
    try {
      const saved = await mailApi.updateSettings({
        enabled,
        channel,
        host: host.trim() || null,
        port: port.trim() ? Number(port) : null,
        username: username.trim() || null,
        password: password || null,
        security,
        fromAddress: fromAddress.trim() || null,
        fromName: fromName.trim() || null,
        wecomCorpId: wecomCorpId.trim() || null,
        wecomSecret: wecomSecret.trim() || null,
        wecomApiBaseUrl: wecomApiBaseUrl.trim() || null,
        companyName: companyName.trim() || null,
        portalBaseUrl: portalBaseUrl.trim() || null,
        contactInfo: contactInfo.trim() || null,
      });
      applySettings(saved);
      showNotice(channel === 'WECOM_API' ? '企业微信邮件 API 配置已保存' : 'SMTP 邮件设置已保存');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSavingSettings(false);
    }
  };

  const handleQueryWecomAccount = async () => {
    setQueryingWecomAccount(true);
    setError(null);
    try {
      const account = await mailApi.queryWeComAccount();
      setWecomAccount(account);
      if (account.email && !fromAddress.trim()) {
        setFromAddress(account.email);
      }
      showNotice(`企业微信应用邮箱连通正常：${account.email || '已授权'}`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setQueryingWecomAccount(false);
    }
  };

  const handleSendTest = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!testTo.trim()) return;
    setSendingTest(true);
    setError(null);
    try {
      await mailApi.sendTest(testTo.trim());
      showNotice(`测试邮件已发送至 ${testTo.trim()}`);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSendingTest(false);
    }
  };

  const handleSaveTemplate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedCode) return;
    setSavingTpl(true);
    setError(null);
    try {
      const updated = await mailApi.updateTemplate(selectedCode, {
        subject: tplSubject,
        body: tplBody,
        enabled: tplEnabled,
      });
      setTemplates(prev => prev.map(t => (t.code === updated.code ? updated : t)));
      selectTemplate(updated);
      showNotice('邮件模板已保存');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSavingTpl(false);
    }
  };

  const handleResetTemplate = async () => {
    if (!selectedCode) return;
    setSavingTpl(true);
    setError(null);
    try {
      const reset = await mailApi.resetTemplate(selectedCode);
      setTemplates(prev => prev.map(t => (t.code === reset.code ? reset : t)));
      selectTemplate(reset);
      showNotice('已恢复默认模板内容');
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSavingTpl(false);
    }
  };

  const handleResend = async (id: number) => {
    setResendingId(id);
    setError(null);
    try {
      await mailApi.resendLog(id);
      showNotice('邮件已重新入队发送');
      await loadLogs();
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setResendingId(null);
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  const currentTpl = templates.find(t => t.code === selectedCode);
  const totalLogPages = Math.max(1, Math.ceil(logTotal / LOG_PAGE_SIZE));
  const inputClass =
    'w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-sm text-slate-800 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100';
  const labelClass = 'mb-1 block text-xs font-medium text-slate-600 dark:text-slate-300';

  return (
    <div className="mx-auto max-w-5xl space-y-6 pb-16">
      <div>
        <h1 className="text-2xl font-bold text-slate-900 dark:text-white">邮件设置</h1>
        <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
          支持企业微信邮件 API 与标准 SMTP 双通道发信（密钥 AES-GCM 加密保存）、通知邮件模板及发件箱重试记录
        </p>
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

      <div className="flex flex-wrap gap-2 border-b border-slate-200 pb-2 dark:border-slate-700">
        {[
          { key: 'smtp', label: '发件通道配置', icon: Settings2 },
          { key: 'templates', label: '邮件模板', icon: FileText },
          { key: 'logs', label: '发送日志', icon: Mail },
        ].map(item => (
          <button
            key={item.key}
            type="button"
            onClick={() => setTab(item.key as MailTab)}
            className={`flex items-center gap-1.5 rounded-xl px-4 py-2 text-sm font-medium transition ${
              tab === item.key
                ? 'bg-primary-500 text-white'
                : 'text-slate-600 hover:bg-slate-100 dark:text-slate-300 dark:hover:bg-slate-800'
            }`}
          >
            <item.icon className="h-4 w-4" />
            {item.label}
          </button>
        ))}
      </div>

      {/* 1. 发件通道设置（SMTP / 企业微信邮件 API） */}
      {tab === 'smtp' && (
        <div className="space-y-6">
          <form onSubmit={handleSaveSettings} className="dark-card space-y-5 p-6">
            <label className="flex items-center gap-2 text-sm font-medium text-slate-800 dark:text-slate-200">
              <input
                type="checkbox"
                checked={enabled}
                onChange={e => setEnabled(e.target.checked)}
                className="h-4 w-4 accent-primary-500"
              />
              启用邮件发送服务（候选人邮箱验证码登录与面试流程通知依赖此项）
            </label>

            <div>
              <label className={labelClass}>发送通道</label>
              <div className="grid gap-3 sm:grid-cols-2">
                <button
                  type="button"
                  onClick={() => setChannel('SMTP')}
                  className={`flex items-start gap-3 rounded-xl border p-3.5 text-left transition ${
                    channel === 'SMTP'
                      ? 'border-primary-500 bg-primary-50/70 dark:border-primary-400 dark:bg-primary-900/20'
                      : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
                  }`}
                >
                  <Server className="mt-0.5 h-5 w-5 shrink-0 text-primary-500" />
                  <div>
                    <div className="text-sm font-semibold text-slate-900 dark:text-white">SMTP 发件服务器</div>
                    <div className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                      通过标准 SMTP / SSL / STARTTLS 协议及邮箱授权码发送邮件
                    </div>
                  </div>
                </button>

                <button
                  type="button"
                  onClick={() => setChannel('WECOM_API')}
                  className={`flex items-start gap-3 rounded-xl border p-3.5 text-left transition ${
                    channel === 'WECOM_API'
                      ? 'border-primary-500 bg-primary-50/70 dark:border-primary-400 dark:bg-primary-900/20'
                      : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
                  }`}
                >
                  <Building2 className="mt-0.5 h-5 w-5 shrink-0 text-primary-500" />
                  <div>
                    <div className="text-sm font-semibold text-slate-900 dark:text-white">企业微信邮件 API</div>
                    <div className="mt-0.5 text-xs text-slate-500 dark:text-slate-400">
                      调用企业微信开放接口发信（应用自动分配专属发信邮箱，支持附件）
                    </div>
                  </div>
                </button>
              </div>
            </div>

            {channel === 'WECOM_API' ? (
              <>
                <div className="rounded-xl border border-blue-100 bg-blue-50/70 p-4 text-xs leading-relaxed text-slate-700 dark:border-blue-900/40 dark:bg-blue-950/30 dark:text-slate-300">
                  <div className="flex items-center gap-1.5 font-semibold text-blue-800 dark:text-blue-300">
                    <Info className="h-4 w-4 shrink-0" />
                    企业微信邮件 API 接入指引
                  </div>
                  <p className="mt-1">
                    1. 企业管理员登录企业微信管理后台，进入「协作 - 邮件 - 可调用接口的应用」，勾选用于招聘发信的自建应用（或授权具备「邮件」权限的应用）。
                  </p>
                  <p className="mt-0.5">
                    2. 应用获得权限后，企业微信会自动为其分配专属应用邮箱账号并作为发件人调用{' '}
                    <code className="rounded bg-white/80 px-1 py-0.5 dark:bg-slate-800">
                      /cgi-bin/exmail/app/compose_send
                    </code>{' '}
                    发送验证码、通知及线下面试 <code className="rounded bg-white/80 px-1 py-0.5 dark:bg-slate-800">invite.ics</code> 附件。
                  </p>
                </div>

                <div className="grid gap-4 md:grid-cols-2">
                  <div>
                    <label className={labelClass}>企业 ID (CorpID)</label>
                    <input
                      value={wecomCorpId}
                      onChange={e => setWecomCorpId(e.target.value)}
                      placeholder="如：ww1234567890abcdef"
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <label className={labelClass}>
                      邮件应用 Secret {settings?.wecomSecretSet ? '（已加密保存，留空保持不变）' : ''}
                    </label>
                    <input
                      type="password"
                      value={wecomSecret}
                      onChange={e => setWecomSecret(e.target.value)}
                      placeholder={settings?.wecomSecretSet ? '••••••••（留空不修改）' : '请输入具备邮件权限的应用 Secret'}
                      className={inputClass}
                    />
                  </div>

                  <div>
                    <label className={labelClass}>企业微信 API 地址（可选）</label>
                    <input
                      value={wecomApiBaseUrl}
                      onChange={e => setWecomApiBaseUrl(e.target.value)}
                      placeholder="默认 https://qyapi.weixin.qq.com"
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <div className="mb-1 flex items-center justify-between">
                      <label className="text-xs font-medium text-slate-600 dark:text-slate-300">
                        应用发信邮箱 / 日历组织者邮箱（可选）
                      </label>
                      {settings?.wecomSecretSet && (
                        <button
                          type="button"
                          disabled={queryingWecomAccount}
                          onClick={handleQueryWecomAccount}
                          className="inline-flex items-center gap-1 text-xs font-medium text-primary-600 hover:underline disabled:opacity-50 dark:text-primary-400"
                        >
                          {queryingWecomAccount ? (
                            <Loader2 className="h-3 w-3 animate-spin" />
                          ) : (
                            <Search className="h-3 w-3" />
                          )}
                          查询应用发信邮箱
                        </button>
                      )}
                    </div>
                    <input
                      type="email"
                      value={fromAddress}
                      onChange={e => setFromAddress(e.target.value)}
                      placeholder="可选：用于线下面试 .ics 日历附件 ORGANIZER"
                      className={inputClass}
                    />
                    {wecomAccount && (
                      <p className="mt-1 text-xs text-emerald-600 dark:text-emerald-400">
                        当前应用主发信邮箱：{wecomAccount.email || '未返回'}
                        {wecomAccount.aliasList.length > 0 ? `（别名：${wecomAccount.aliasList.join('、')}）` : ''}
                      </p>
                    )}
                  </div>
                </div>
              </>
            ) : (
              <div className="grid gap-4 md:grid-cols-2">
                <div>
                  <label className={labelClass}>SMTP 服务器主机</label>
                  <input
                    value={host}
                    onChange={e => setHost(e.target.value)}
                    placeholder="如：smtp.exmail.qq.com"
                    className={inputClass}
                  />
                </div>
                <div className="grid grid-cols-2 gap-3">
                  <div>
                    <label className={labelClass}>端口</label>
                    <input
                      type="number"
                      min={1}
                      max={65535}
                      value={port}
                      onChange={e => setPort(e.target.value)}
                      className={inputClass}
                    />
                  </div>
                  <div>
                    <label className={labelClass}>连接加密</label>
                    <select
                      value={security}
                      onChange={e => setSecurity(e.target.value as MailSecurity)}
                      className={inputClass}
                    >
                      {(Object.entries(MAIL_SECURITY_LABELS) as [MailSecurity, string][]).map(([val, label]) => (
                        <option key={val} value={val}>
                          {label}
                        </option>
                      ))}
                    </select>
                  </div>
                </div>

                <div>
                  <label className={labelClass}>SMTP 登录账号</label>
                  <input
                    value={username}
                    onChange={e => setUsername(e.target.value)}
                    placeholder="如：hr@company.com"
                    className={inputClass}
                  />
                </div>
                <div>
                  <label className={labelClass}>
                    SMTP 授权码 / 密码 {settings?.passwordSet ? '（已设置，留空保持不变）' : ''}
                  </label>
                  <input
                    type="password"
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    placeholder={settings?.passwordSet ? '••••••••（留空不修改）' : '请输入 SMTP 密码或授权码'}
                    className={inputClass}
                  />
                </div>

                <div>
                  <label className={labelClass}>发件人邮箱</label>
                  <input
                    type="email"
                    value={fromAddress}
                    onChange={e => setFromAddress(e.target.value)}
                    placeholder="如：hr@company.com"
                    className={inputClass}
                  />
                </div>
                <div>
                  <label className={labelClass}>发件人显示名称</label>
                  <input
                    value={fromName}
                    onChange={e => setFromName(e.target.value)}
                    placeholder="如：XX 科技招聘团队"
                    className={inputClass}
                  />
                </div>
              </div>
            )}

            <div className="grid gap-4 border-t border-slate-100 pt-4 dark:border-slate-800 md:grid-cols-2">
              <div>
                <label className={labelClass}>公司名称（用于邮件模板与日历邀请）</label>
                <input
                  value={companyName}
                  onChange={e => setCompanyName(e.target.value)}
                  placeholder="如：XX 科技"
                  className={inputClass}
                />
              </div>
              <div>
                <label className={labelClass}>招聘门户地址（用于邮件中的链接）</label>
                <input
                  value={portalBaseUrl}
                  onChange={e => setPortalBaseUrl(e.target.value)}
                  placeholder="如：https://jobs.example.com"
                  className={inputClass}
                />
              </div>

              <div className="md:col-span-2">
                <label className={labelClass}>HR 联系方式（展示在邮件落款）</label>
                <input
                  value={contactInfo}
                  onChange={e => setContactInfo(e.target.value)}
                  placeholder="如：如有疑问请联系 hr@company.com / 010-88888888"
                  className={inputClass}
                />
              </div>
            </div>

            <div className="flex justify-end pt-2">
              <button
                type="submit"
                disabled={savingSettings}
                className="btn-primary flex items-center gap-2 rounded-xl px-5 py-2 text-sm disabled:opacity-60"
              >
                {savingSettings ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
                {channel === 'WECOM_API' ? '保存企业微信配置' : '保存 SMTP 配置'}
              </button>
            </div>
          </form>

          <form onSubmit={handleSendTest} className="dark-card flex flex-wrap items-end gap-3 p-6">
            <div className="flex-1 min-w-[240px]">
              <label className={labelClass}>
                发送测试邮件（需先保存并启用当前{channel === 'WECOM_API' ? '企业微信邮件 API' : ' SMTP'} 配置）
              </label>
              <input
                type="email"
                required
                value={testTo}
                onChange={e => setTestTo(e.target.value)}
                placeholder="输入测试收件邮箱"
                className={inputClass}
              />
            </div>
            <button
              type="submit"
              disabled={sendingTest}
              className="btn-secondary flex items-center gap-2 rounded-xl px-4 py-2 text-sm disabled:opacity-60"
            >
              {sendingTest ? <Loader2 className="h-4 w-4 animate-spin" /> : <Send className="h-4 w-4" />}
              发送测试邮件
            </button>
          </form>
        </div>
      )}

      {/* 2. 邮件模板 */}
      {tab === 'templates' && (
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="dark-card space-y-1 p-3">
            {templates.map(t => (
              <button
                key={t.code}
                type="button"
                onClick={() => selectTemplate(t)}
                className={`flex w-full items-center justify-between rounded-xl px-3 py-2.5 text-left text-sm transition ${
                  selectedCode === t.code
                    ? 'bg-primary-50 font-semibold text-primary-700 dark:bg-primary-900/30 dark:text-primary-300'
                    : 'text-slate-700 hover:bg-slate-50 dark:text-slate-300 dark:hover:bg-slate-800'
                }`}
              >
                <span>{t.name}</span>
                <span className="text-xs text-slate-400">{t.enabled ? '已启用' : '已停用'}</span>
              </button>
            ))}
          </div>

          {currentTpl && (
            <form onSubmit={handleSaveTemplate} className="dark-card space-y-4 p-6 lg:col-span-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <h3 className="text-base font-semibold text-slate-900 dark:text-white">{currentTpl.name}</h3>
                  <p className="text-xs text-slate-400">
                    编码：{currentTpl.code} {currentTpl.customized ? '· 已自定义' : '· 默认内容'}
                  </p>
                </div>
                {currentTpl.code !== 'VERIFICATION_CODE' && (
                  <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
                    <input
                      type="checkbox"
                      checked={tplEnabled}
                      onChange={e => setTplEnabled(e.target.checked)}
                      className="h-4 w-4 accent-primary-500"
                    />
                    启用该通知
                  </label>
                )}
              </div>

              <div>
                <label className={labelClass}>可用变量（正文中写为 {'{变量名}'}）</label>
                <div className="flex flex-wrap gap-1.5">
                  {[...currentTpl.variables, ...COMMON_VARIABLES].map(v => (
                    <code
                      key={v}
                      className="rounded bg-slate-100 px-2 py-0.5 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300"
                    >
                      {`{${v}}`}
                    </code>
                  ))}
                </div>
              </div>

              <div>
                <label className={labelClass}>邮件主题 *</label>
                <input
                  required
                  maxLength={255}
                  value={tplSubject}
                  onChange={e => setTplSubject(e.target.value)}
                  className={inputClass}
                />
              </div>

              <div>
                <label className={labelClass}>邮件正文 *</label>
                <textarea
                  required
                  rows={10}
                  maxLength={20000}
                  value={tplBody}
                  onChange={e => setTplBody(e.target.value)}
                  className={`${inputClass} font-mono`}
                />
              </div>

              <div className="flex justify-between pt-2">
                {currentTpl.customized ? (
                  <button
                    type="button"
                    disabled={savingTpl}
                    onClick={handleResetTemplate}
                    className="btn-secondary flex items-center gap-1.5 rounded-xl px-4 py-2 text-sm"
                  >
                    <RotateCcw className="h-4 w-4" />
                    恢复默认
                  </button>
                ) : (
                  <span />
                )}
                <button
                  type="submit"
                  disabled={savingTpl}
                  className="btn-primary flex items-center gap-2 rounded-xl px-5 py-2 text-sm disabled:opacity-60"
                >
                  {savingTpl ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
                  保存模板
                </button>
              </div>
            </form>
          )}
        </div>
      )}

      {/* 3. 发送日志 */}
      {tab === 'logs' && (
        <div className="dark-card overflow-hidden">
          <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 p-4 dark:border-slate-800">
            <div className="flex items-center gap-2">
              <select
                value={logStatus}
                onChange={e => {
                  setLogStatus(e.target.value as EmailLogStatus | '');
                  setLogPage(1);
                }}
                className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-800 dark:text-slate-200"
              >
                <option value="">全部状态</option>
                {(Object.entries(EMAIL_LOG_STATUS_LABELS) as [EmailLogStatus, string][]).map(([val, label]) => (
                  <option key={val} value={val}>
                    {label}
                  </option>
                ))}
              </select>
            </div>
            <button
              type="button"
              onClick={loadLogs}
              className="btn-secondary flex items-center gap-1.5 rounded-xl px-3 py-1.5 text-sm"
            >
              <RefreshCw className="h-4 w-4" />
              刷新
            </button>
          </div>

          {logsLoading ? (
            <div className="flex justify-center py-16">
              <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
            </div>
          ) : logs.length === 0 ? (
            <div className="py-16 text-center text-sm text-slate-400">暂无邮件发送记录</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead className="border-b border-slate-100 bg-slate-50/70 text-xs font-semibold text-slate-500 dark:border-slate-800 dark:bg-slate-800/60">
                  <tr>
                    <th className="px-4 py-3">收件人</th>
                    <th className="px-4 py-3">主题</th>
                    <th className="px-4 py-3">状态</th>
                    <th className="px-4 py-3">时间</th>
                    <th className="px-4 py-3 text-right">操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-slate-100 dark:divide-slate-800">
                  {logs.map(item => (
                    <tr key={item.id}>
                      <td className="px-4 py-3 text-slate-700 dark:text-slate-200">{item.toAddress}</td>
                      <td className="px-4 py-3">
                        <div className="text-slate-800 dark:text-slate-100">{item.subject}</div>
                        {item.error && <div className="text-xs text-rose-500">{item.error}</div>}
                      </td>
                      <td className="px-4 py-3 text-xs">
                        {EMAIL_LOG_STATUS_LABELS[item.status]}
                        {item.retryCount > 0 ? `（重试 ${item.retryCount}）` : ''}
                      </td>
                      <td className="px-4 py-3 text-xs text-slate-500">
                        {formatDateTime(item.sentAt ?? item.createdAt)}
                      </td>
                      <td className="px-4 py-3 text-right">
                        {item.templateCode !== 'VERIFICATION_CODE' && (
                          <button
                            type="button"
                            disabled={resendingId === item.id}
                            onClick={() => handleResend(item.id)}
                            className="text-xs font-medium text-primary-600 hover:underline disabled:opacity-50 dark:text-primary-400"
                          >
                            重发
                          </button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {logTotal > LOG_PAGE_SIZE && (
            <div className="flex items-center justify-between border-t border-slate-100 px-4 py-3 text-xs text-slate-500 dark:border-slate-800">
              <span>
                第 {logPage} / {totalLogPages} 页 · 共 {logTotal} 条
              </span>
              <div className="flex items-center gap-2">
                <button
                  type="button"
                  disabled={logPage <= 1}
                  onClick={() => setLogPage(p => Math.max(1, p - 1))}
                  className="btn-secondary flex items-center gap-1 rounded-lg px-2.5 py-1 disabled:opacity-40"
                >
                  <ChevronLeft className="h-3.5 w-3.5" />
                  上一页
                </button>
                <button
                  type="button"
                  disabled={logPage >= totalLogPages}
                  onClick={() => setLogPage(p => Math.min(totalLogPages, p + 1))}
                  className="btn-secondary flex items-center gap-1 rounded-lg px-2.5 py-1 disabled:opacity-40"
                >
                  下一页
                  <ChevronRight className="h-3.5 w-3.5" />
                </button>
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
