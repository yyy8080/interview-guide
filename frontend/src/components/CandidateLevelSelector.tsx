import { Target } from 'lucide-react';
import { CANDIDATE_LEVEL_OPTIONS, type JobDifficulty } from '../types/job';

interface CandidateLevelSelectorProps {
  value: JobDifficulty;
  onChange: (level: JobDifficulty) => void;
  recommendedLevel?: JobDifficulty | null;
  disabled?: boolean;
  label?: string;
  hint?: string;
  testIdPrefix?: string;
}

export default function CandidateLevelSelector({
  value,
  onChange,
  recommendedLevel,
  disabled = false,
  label = '选择您的应聘定位',
  hint = 'AI 将按您选择的定位调整考核深度与出题方向',
  testIdPrefix = 'candidate-level',
}: CandidateLevelSelectorProps) {
  return (
    <div className="space-y-2" data-testid={`${testIdPrefix}-selector`}>
      <div className="flex flex-wrap items-center justify-between gap-1">
        <label className="flex items-center gap-1.5 text-sm font-semibold text-slate-800 dark:text-slate-200">
          <Target className="h-4 w-4 text-primary-500" />
          {label}
        </label>
        {hint && <span className="text-xs text-slate-500 dark:text-slate-400">{hint}</span>}
      </div>

      <div className="grid gap-3 sm:grid-cols-3">
        {CANDIDATE_LEVEL_OPTIONS.map(opt => {
          const selected = value === opt.value;
          const recommended = recommendedLevel === opt.value;
          return (
            <button
              key={opt.value}
              type="button"
              disabled={disabled}
              data-testid={`${testIdPrefix}-${opt.value}`}
              aria-pressed={selected}
              onClick={() => onChange(opt.value)}
              className={`flex flex-col items-start gap-1 rounded-2xl border p-3.5 text-left transition disabled:opacity-60 ${
                selected
                  ? 'border-primary-500 bg-primary-50/70 ring-1 ring-primary-500/40 dark:border-primary-400 dark:bg-primary-900/25'
                  : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
              }`}
            >
              <div className="flex w-full items-center justify-between gap-1.5">
                <span className="text-sm font-bold text-slate-900 dark:text-white">
                  {opt.label}
                </span>
                <div className="flex items-center gap-1">
                  {recommended && (
                    <span className="rounded-full bg-amber-50 px-2 py-0.5 text-[11px] font-medium text-amber-700 dark:bg-amber-900/30 dark:text-amber-300">
                      岗位建议
                    </span>
                  )}
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                    {opt.badge}
                  </span>
                </div>
              </div>
              <p className="text-xs leading-relaxed text-slate-500 dark:text-slate-400">
                {opt.desc}
              </p>
            </button>
          );
        })}
      </div>
    </div>
  );
}
