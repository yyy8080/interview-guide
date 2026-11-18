import { useEffect, useState } from 'react';
import AiWaitingPanel, { type AiWaitingPanelProps } from './AiWaitingPanel';

interface AiWaitingOverlayProps extends Omit<AiWaitingPanelProps, 'variant'> {
  open: boolean;
  /** 请求超过该时长才展示，避免快速返回时闪烁 */
  delayMs?: number;
}

/**
 * 全屏等待浮层：移动端底部抽屉样式，桌面端居中卡片
 */
export default function AiWaitingOverlay({
  open,
  delayMs = 400,
  testId = 'ai-waiting-overlay',
  ...panelProps
}: AiWaitingOverlayProps) {
  const [visible, setVisible] = useState(false);

  useEffect(() => {
    if (!open) {
      setVisible(false);
      return;
    }
    const timer = window.setTimeout(() => setVisible(true), delayMs);
    return () => window.clearTimeout(timer);
  }, [open, delayMs]);

  useEffect(() => {
    if (!visible) return;
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = prev;
    };
  }, [visible]);

  if (!open || !visible) return null;

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={panelProps.title}
      className="fixed inset-0 z-[70] flex items-end justify-center bg-slate-900/55 backdrop-blur-sm sm:items-center sm:p-4"
    >
      <div className="max-h-[92dvh] w-full overflow-y-auto rounded-t-3xl bg-white p-5 pb-[calc(1.25rem+env(safe-area-inset-bottom))] shadow-2xl sm:max-w-md sm:rounded-3xl sm:p-6 dark:bg-slate-800">
        <div className="mx-auto mb-3 h-1 w-10 rounded-full bg-slate-200 sm:hidden dark:bg-slate-700" />
        <AiWaitingPanel {...panelProps} variant="overlay" testId={testId} />
      </div>
    </div>
  );
}
