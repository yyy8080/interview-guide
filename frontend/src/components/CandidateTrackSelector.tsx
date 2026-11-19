import { Code2, Layers, Server } from 'lucide-react';
import { CANDIDATE_TRACK_OPTIONS, type CandidateTrack } from '../types/recruitment';

interface CandidateTrackSelectorProps {
  value: CandidateTrack;
  onChange: (track: CandidateTrack) => void;
  disabled?: boolean;
  label?: string;
  hint?: string;
  testIdPrefix?: string;
}

export default function CandidateTrackSelector({
  value,
  onChange,
  disabled = false,
  label = '选择技术方向偏好',
  hint = '后续简历初筛、在线笔试与面试出题均按所选方向进行，答题时无需再选',
  testIdPrefix = 'candidate-track',
}: CandidateTrackSelectorProps) {
  return (
    <div className="space-y-2" data-testid={`${testIdPrefix}-selector`}>
      <div className="flex flex-wrap items-center justify-between gap-1">
        <label className="flex items-center gap-1.5 text-sm font-semibold text-slate-800 dark:text-slate-200">
          <Layers className="h-4 w-4 text-primary-500" />
          {label}
        </label>
        {hint && <span className="text-xs text-slate-500 dark:text-slate-400">{hint}</span>}
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        {CANDIDATE_TRACK_OPTIONS.map(opt => {
          const selected = value === opt.value;
          const Icon = opt.value === 'FRONTEND' ? Code2 : Server;
          return (
            <button
              key={opt.value}
              type="button"
              disabled={disabled}
              data-testid={`${testIdPrefix}-${opt.value}`}
              aria-pressed={selected}
              onClick={() => onChange(opt.value)}
              className={`flex flex-col items-start gap-1 rounded-2xl border p-3.5 text-left transition touch-manipulation disabled:opacity-60 ${
                selected
                  ? 'border-primary-500 bg-primary-50/70 ring-1 ring-primary-500/40 dark:border-primary-400 dark:bg-primary-900/25'
                  : 'border-slate-200 hover:border-slate-300 dark:border-slate-700 dark:hover:border-slate-600'
              }`}
            >
              <div className="flex w-full items-center justify-between gap-1.5">
                <span className="flex items-center gap-1.5 text-sm font-bold text-slate-900 dark:text-white">
                  <Icon className="h-4 w-4 text-primary-500" />
                  {opt.label}
                </span>
                <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                  {opt.badge}
                </span>
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
