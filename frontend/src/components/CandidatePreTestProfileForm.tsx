import { Sparkles } from 'lucide-react';
import { AI_EXPERIENCE_LABELS, type AiExperienceLevel } from '../types/recruitment';

interface AiOption {
  value: AiExperienceLevel;
  title: string;
  desc: string;
}

const AI_OPTIONS: AiOption[] = [
  {
    value: 'NONE',
    title: AI_EXPERIENCE_LABELS.NONE,
    desc: '尚未在日常编码或项目中系统使用 AI 工具',
  },
  {
    value: 'TOOL_USER',
    title: AI_EXPERIENCE_LABELS.TOOL_USER,
    desc: '熟悉使用 Cursor、Copilot、ChatGPT 等工具辅助研发提效',
  },
  {
    value: 'PROJECT_PRACTICE',
    title: AI_EXPERIENCE_LABELS.PROJECT_PRACTICE,
    desc: '有 LLM 应用开发、RAG 知识库、Agent 工作流或微调实战经验',
  },
];

interface CandidatePreTestProfileFormProps {
  aiExperience: AiExperienceLevel | null;
  onAiExperienceChange: (next: AiExperienceLevel | null) => void;
  specialties: string;
  onSpecialtiesChange: (next: string) => void;
  disabled?: boolean;
  testIdPrefix?: string;
}

export default function CandidatePreTestProfileForm({
  aiExperience,
  onAiExperienceChange,
  specialties,
  onSpecialtiesChange,
  disabled = false,
  testIdPrefix = 'pretest-profile',
}: CandidatePreTestProfileFormProps) {
  return (
    <div
      data-testid={`${testIdPrefix}-card`}
      className="space-y-4 rounded-2xl border border-slate-200/90 bg-slate-50/70 p-4 dark:border-slate-700/80 dark:bg-slate-800/40"
    >
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="flex items-center gap-1.5 text-sm font-semibold text-slate-800 dark:text-slate-200">
          <Sparkles className="h-4 w-4 text-primary-500" />
          个人特长与 AI 相关经验补充（选填）
        </div>
        <span className="text-xs text-slate-500 dark:text-slate-400">
          便于 AI 结合您的技术亮点提问及 HR 综合评估，不填不影响正常开考
        </span>
      </div>

      {/* 有无 AI 相关经验 */}
      <div className="space-y-2">
        <label className="block text-xs font-medium text-slate-700 dark:text-slate-300">
          有无 AI 相关经验（点击已选项可取消）
        </label>
        <div className="grid gap-2.5 sm:grid-cols-3">
          {AI_OPTIONS.map(opt => {
            const active = aiExperience === opt.value;
            return (
              <button
                key={opt.value}
                type="button"
                disabled={disabled}
                data-testid={`${testIdPrefix}-ai-${opt.value}`}
                aria-pressed={active}
                onClick={() => onAiExperienceChange(active ? null : opt.value)}
                className={`rounded-xl border p-3 text-left transition disabled:opacity-60 ${
                  active
                    ? 'border-primary-500 bg-primary-50/80 ring-1 ring-primary-500/30 dark:border-primary-400 dark:bg-primary-900/30'
                    : 'border-slate-200 bg-white hover:border-slate-300 dark:border-slate-700 dark:bg-slate-800/80 dark:hover:border-slate-600'
                }`}
              >
                <div className="flex items-center justify-between gap-1.5">
                  <span
                    className={`text-xs font-semibold ${
                      active
                        ? 'text-primary-700 dark:text-primary-300'
                        : 'text-slate-800 dark:text-slate-100'
                    }`}
                  >
                    {opt.title}
                  </span>
                  {active && (
                    <span className="rounded-full bg-primary-500 px-1.5 py-0.5 text-[10px] font-medium text-white">
                      已选
                    </span>
                  )}
                </div>
                <p className="mt-1 text-[11px] leading-snug text-slate-500 dark:text-slate-400">
                  {opt.desc}
                </p>
              </button>
            );
          })}
        </div>
      </div>

      {/* 其他特长说明 */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between text-xs">
          <label
            htmlFor={`${testIdPrefix}-specialties-input`}
            className="font-medium text-slate-700 dark:text-slate-300"
          >
            其他个人特长 / 技术亮点补充（选填）
          </label>
          <span className="text-slate-400">{specialties.length} / 1000</span>
        </div>
        <textarea
          id={`${testIdPrefix}-specialties-input`}
          data-testid={`${testIdPrefix}-specialties`}
          rows={2}
          maxLength={1000}
          disabled={disabled}
          value={specialties}
          onChange={e => onSpecialtiesChange(e.target.value)}
          placeholder="例如：擅长 JVM 与 SQL 性能调优、主导过微服务高可用改造、开源项目贡献者、搭建过 Spring AI / LangChain 知识库问答等…"
          className="w-full rounded-xl border border-slate-200 bg-white px-3.5 py-2 text-xs leading-relaxed text-slate-900 focus:border-primary-500 focus:outline-none disabled:opacity-60 dark:border-slate-700 dark:bg-slate-800 dark:text-white"
        />
      </div>
    </div>
  );
}
