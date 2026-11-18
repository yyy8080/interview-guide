import { useEffect, useState } from 'react';
import { AnimatePresence, motion } from 'framer-motion';
import { ChevronDown, ChevronUp, Gamepad2, Lightbulb, Sparkles } from 'lucide-react';
import MemoryMatchGame from './MemoryMatchGame';
import { GENERAL_WAITING_TIPS, useRotatingTip } from './waitingTips';

export interface AiWaitingPanelProps {
  title: string;
  description?: string;
  /** 预计耗时说明，如「通常需要 20–60 秒」 */
  estimate?: string;
  tips?: string[];
  /** 是否提供小游戏入口 */
  allowGame?: boolean;
  /** 默认是否展开小游戏 */
  defaultGameOpen?: boolean;
  /** 是否显示已等待秒数 */
  showElapsed?: boolean;
  /** 卡片样式（内嵌页面时使用） */
  variant?: 'overlay' | 'card';
  testId?: string;
}

/**
 * AI 生成 / 评估等待面板：动效 + 已等待时长 + 轮播小贴士 + 可选记忆翻牌小游戏
 */
export default function AiWaitingPanel({
  title,
  description,
  estimate,
  tips = GENERAL_WAITING_TIPS,
  allowGame = true,
  defaultGameOpen = false,
  showElapsed = true,
  variant = 'overlay',
  testId = 'ai-waiting-panel',
}: AiWaitingPanelProps) {
  const [elapsed, setElapsed] = useState(0);
  const [gameOpen, setGameOpen] = useState(defaultGameOpen);
  const tip = useRotatingTip(tips, 6000);

  useEffect(() => {
    if (!showElapsed) return;
    const startedAt = Date.now();
    const timer = window.setInterval(() => {
      setElapsed(Math.floor((Date.now() - startedAt) / 1000));
    }, 1000);
    return () => window.clearInterval(timer);
  }, [showElapsed]);

  const wrapperClass =
    variant === 'card'
      ? 'space-y-4 rounded-2xl border border-primary-100 bg-gradient-to-br from-primary-50/70 via-white to-indigo-50/60 p-4 sm:p-5 dark:border-primary-900/40 dark:from-primary-900/20 dark:via-slate-900/40 dark:to-indigo-900/10'
      : 'space-y-4';

  return (
    <div className={wrapperClass} data-testid={testId} aria-live="polite">
      <div className="flex items-start gap-3">
        <div className="relative flex h-11 w-11 shrink-0 items-center justify-center">
          <span className="absolute inset-0 animate-spin rounded-full border-[3px] border-primary-100 border-t-primary-500 dark:border-primary-900/50 dark:border-t-primary-400" />
          <Sparkles className="h-5 w-5 text-primary-500 dark:text-primary-400" />
        </div>
        <div className="min-w-0 flex-1">
          <div className="text-base font-semibold text-slate-900 dark:text-white">{title}</div>
          {description && (
            <p className="mt-0.5 text-xs leading-relaxed text-slate-500 dark:text-slate-400">{description}</p>
          )}
          {(showElapsed || estimate) && (
            <p className="mt-1 text-[11px] text-slate-400 dark:text-slate-500">
              {showElapsed ? `已等待 ${elapsed} 秒` : ''}
              {showElapsed && estimate ? ' · ' : ''}
              {estimate ?? ''}
            </p>
          )}
        </div>
      </div>

      <div className="h-1.5 overflow-hidden rounded-full bg-primary-100/80 dark:bg-primary-900/40">
        <div className="ig-indeterminate-bar h-full w-1/3 rounded-full bg-gradient-to-r from-primary-400 to-indigo-500" />
      </div>

      {tip && (
        <div className="flex min-h-[52px] items-start gap-2 rounded-xl bg-amber-50/80 px-3.5 py-2.5 text-xs leading-relaxed text-amber-900 dark:bg-amber-900/20 dark:text-amber-100">
          <Lightbulb className="mt-0.5 h-4 w-4 shrink-0 text-amber-500" />
          <AnimatePresence mode="wait">
            <motion.span
              key={tip}
              initial={{ opacity: 0, y: 4 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -4 }}
              transition={{ duration: 0.25 }}
            >
              {tip}
            </motion.span>
          </AnimatePresence>
        </div>
      )}

      {allowGame && (
        <div className="space-y-3">
          <button
            type="button"
            data-testid={`${testId}-game-toggle`}
            onClick={() => setGameOpen(v => !v)}
            className="flex w-full items-center justify-between rounded-xl border border-slate-200 bg-white/80 px-3.5 py-2.5 text-xs font-medium text-slate-700 transition hover:border-primary-300 dark:border-slate-700 dark:bg-slate-900/60 dark:text-slate-200"
          >
            <span className="flex items-center gap-1.5">
              <Gamepad2 className="h-4 w-4 text-primary-500" />
              {gameOpen ? '收起小游戏' : '等待有点久？玩一局记忆翻牌放松一下'}
            </span>
            {gameOpen ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
          </button>
          {gameOpen && (
            <>
              <MemoryMatchGame />
              <p className="text-center text-[11px] text-slate-400 dark:text-slate-500">
                AI 处理完成后将自动继续，无需等待游戏结束
              </p>
            </>
          )}
        </div>
      )}
    </div>
  );
}
