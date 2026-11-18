import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { CheckCircle2, KeyRound, Loader2, Mail, ShieldAlert } from 'lucide-react';
import { candidateAuthApi } from '../../api/candidate';
import { getErrorMessage } from '../../api/request';
import { ROUTES } from '../../constants/routes';
import { saveCandidateAuth } from '../../utils/candidateStorage';

function resolveRedirect(raw: string | null): string {
  if (!raw || !raw.startsWith('/careers') || raw.startsWith('//')) {
    return ROUTES.careerMe;
  }
  return raw;
}

export default function CandidateLoginPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const redirectTarget = resolveRedirect(searchParams.get('redirect'));

  const [email, setEmail] = useState(() => searchParams.get('email')?.trim() ?? '');
  const [code, setCode] = useState('');
  const [countdown, setCountdown] = useState(0);
  const [sending, setSending] = useState(false);
  const [verifying, setVerifying] = useState(false);
  const [codeSent, setCodeSent] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const queryEmail = searchParams.get('email')?.trim();
    if (queryEmail) {
      setEmail(queryEmail);
    }
  }, [searchParams]);

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = window.setInterval(() => {
      setCountdown(prev => (prev <= 1 ? 0 : prev - 1));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [countdown]);

  const handleSendCode = async () => {
    const trimmed = email.trim();
    if (!trimmed) {
      setError('请输入邮箱地址');
      return;
    }
    setSending(true);
    setError(null);
    try {
      await candidateAuthApi.sendCode(trimmed);
      setCodeSent(true);
      setCountdown(60);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSending(false);
    }
  };

  const handleVerify = async (e: React.FormEvent) => {
    e.preventDefault();
    const trimmedEmail = email.trim();
    const trimmedCode = code.trim();
    if (!trimmedEmail || !/^\d{6}$/.test(trimmedCode)) {
      setError('请输入邮箱及 6 位数字验证码');
      return;
    }
    setVerifying(true);
    setError(null);
    try {
      const res = await candidateAuthApi.verify(trimmedEmail, trimmedCode);
      saveCandidateAuth(res);
      navigate(redirectTarget, { replace: true });
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setVerifying(false);
    }
  };

  return (
    <div className="mx-auto max-w-md py-8">
      <div className="dark-card space-y-6 p-6 sm:p-8">
        <div className="text-center">
          <div className="mx-auto mb-3 flex h-12 w-12 items-center justify-center rounded-2xl bg-primary-50 text-primary-600 dark:bg-primary-900/30 dark:text-primary-400">
            <Mail className="h-6 w-6" />
          </div>
          <h1 className="text-xl font-bold text-slate-900 dark:text-white">候选人受邀邮箱验证</h1>
          <p className="mt-1 text-sm text-slate-500 dark:text-slate-400">
            请使用收到面试邀请邮件的邮箱获取验证码并进入投递或面试
          </p>
        </div>

        <div
          data-testid="invite-only-login-tip"
          className="flex items-start gap-2.5 rounded-xl border border-amber-200/80 bg-amber-50/70 px-3.5 py-2.5 text-xs leading-relaxed text-amber-900 dark:border-amber-800/60 dark:bg-amber-900/20 dark:text-amber-200"
        >
          <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-600 dark:text-amber-400" />
          <span>
            受邀准入说明：面试仅允许收到邀请的邮箱进入，<strong>非受邀邮箱不允许投递简历与参加面试</strong>。
          </span>
        </div>

        {codeSent && !error && (
          <div className="flex items-center gap-2 rounded-xl bg-emerald-50 px-3.5 py-2.5 text-xs text-emerald-700 dark:bg-emerald-900/20 dark:text-emerald-300">
            <CheckCircle2 className="h-4 w-4 shrink-0" />
            验证码已发送至您的邮箱，10 分钟内有效
          </div>
        )}

        {error && (
          <div
            data-testid="candidate-login-error"
            className="rounded-xl bg-red-50 px-3.5 py-2.5 text-xs text-red-600 dark:bg-red-900/20 dark:text-red-400"
          >
            {error}
          </div>
        )}

        <form onSubmit={handleVerify} className="space-y-4">
          <div>
            <label className="mb-1.5 block text-xs font-medium text-slate-600 dark:text-slate-300">
              个人邮箱
            </label>
            <input
              type="email"
              required
              value={email}
              onChange={e => setEmail(e.target.value)}
              placeholder="name@example.com"
              className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-white"
            />
          </div>

          <div>
            <label className="mb-1.5 block text-xs font-medium text-slate-600 dark:text-slate-300">
              6 位验证码
            </label>
            <div className="flex gap-2">
              <div className="relative flex-1">
                <KeyRound className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-slate-400" />
                <input
                  inputMode="numeric"
                  maxLength={6}
                  required
                  value={code}
                  onChange={e => setCode(e.target.value.replace(/\D/g, ''))}
                  placeholder="请输入验证码"
                  className="w-full rounded-xl border border-slate-200 bg-white py-2.5 pl-9 pr-3 text-sm tracking-widest text-slate-900 focus:border-primary-500 focus:outline-none dark:border-slate-700 dark:bg-slate-800 dark:text-white"
                />
              </div>
              <button
                type="button"
                disabled={sending || countdown > 0}
                onClick={handleSendCode}
                className="btn-secondary shrink-0 rounded-xl px-4 py-2.5 text-xs font-medium disabled:opacity-50"
              >
                {sending ? '发送中…' : countdown > 0 ? `${countdown}s 后重发` : '获取验证码'}
              </button>
            </div>
          </div>

          <button
            type="submit"
            disabled={verifying}
            className="btn-primary flex w-full items-center justify-center gap-2 rounded-xl py-2.5 text-sm font-medium disabled:opacity-60"
          >
            {verifying && <Loader2 className="h-4 w-4 animate-spin" />}
            验证并继续
          </button>
        </form>
      </div>
    </div>
  );
}
