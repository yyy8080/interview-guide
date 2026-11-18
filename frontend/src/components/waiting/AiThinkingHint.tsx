import { Loader2 } from 'lucide-react';
import { THINKING_TIPS, useRotatingTip } from './waitingTips';

interface AiThinkingHintProps {
  active: boolean;
  tips?: string[];
  testId?: string;
}

/**
 * 作答计时中的轻量提示：仅一行轮播文案，不打断作答节奏
 */
export default function AiThinkingHint({ active, tips = THINKING_TIPS, testId = 'ai-thinking-hint' }: AiThinkingHintProps) {
  const tip = useRotatingTip(tips, 3500, active);
  if (!active) return null;
  return (
    <div
      data-testid={testId}
      aria-live="polite"
      className="flex items-center gap-2 rounded-xl bg-primary-50/80 px-3.5 py-2 text-xs text-primary-700 dark:bg-primary-900/25 dark:text-primary-300"
    >
      <Loader2 className="h-3.5 w-3.5 shrink-0 animate-spin" />
      <span>{tip}</span>
    </div>
  );
}
