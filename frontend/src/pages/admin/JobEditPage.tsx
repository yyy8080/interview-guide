import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Loader2, Save, Send, Sparkles, X } from 'lucide-react';
import { jobApi } from '../../api/job';
import { getErrorMessage } from '../../api/request';
import { skillApi, type CategoryDTO, type SkillDTO } from '../../api/skill';
import { knowledgeBaseApi, type KnowledgeBaseItem } from '../../api/knowledgebase';
import { llmProviderApi } from '../../api/llmProvider';
import type { ProviderItem } from '../../types/llmProvider';
import { ROUTES } from '../../constants/routes';
import { getStoredUser } from '../../utils/authStorage';
import {
  EMPLOYMENT_TYPE_LABELS,
  INTERVIEW_MODE_LABELS,
  JOB_DIFFICULTY_LABELS,
  JOB_STATUS_LABELS,
  type EmploymentType,
  type InterviewMode,
  type JobDifficulty,
  type JobPosition,
  type JobPositionRequest,
  type JobStatus,
} from '../../types/job';

const CUSTOM_SKILL_ID = 'custom';

const PRIORITY_LABELS: Record<CategoryDTO['priority'], string> = {
  CORE: '核心',
  NORMAL: '普通',
  ALWAYS_ONE: '必考一题',
};

const PRIORITY_ORDER: CategoryDTO['priority'][] = ['CORE', 'NORMAL', 'ALWAYS_ONE'];

interface FormState {
  title: string;
  department: string;
  location: string;
  employmentType: EmploymentType;
  headcount: string;
  salaryRange: string;
  jdText: string;
  categories: CategoryDTO[];
  skillId: string;
  difficulty: JobDifficulty;
  questionCount: number;
  interviewMode: InterviewMode;
  knowledgeBaseId: string;
  timeLimitMinutes: string;
  llmProvider: string;
  screeningEnabled: boolean;
  screeningThreshold: string;
  aiPassThreshold: number;
  proctorAwayReminder: boolean;
  proctorWatermark: boolean;
  writtenTestEnabled: boolean;
  writtenSingleCount: number;
  writtenMultiCount: number;
  writtenFillCount: number;
  writtenTimeLimitMinutes: number;
  writtenWeight: number;
  deadline: string;
}

const EMPTY_FORM: FormState = {
  title: '',
  department: '',
  location: '',
  employmentType: 'FULL_TIME',
  headcount: '',
  salaryRange: '',
  jdText: '',
  categories: [],
  skillId: CUSTOM_SKILL_ID,
  difficulty: 'mid',
  questionCount: 8,
  interviewMode: 'BOTH',
  knowledgeBaseId: '',
  timeLimitMinutes: '45',
  llmProvider: '',
  screeningEnabled: true,
  screeningThreshold: '60',
  aiPassThreshold: 70,
  proctorAwayReminder: true,
  proctorWatermark: true,
  writtenTestEnabled: false,
  writtenSingleCount: 5,
  writtenMultiCount: 3,
  writtenFillCount: 2,
  writtenTimeLimitMinutes: 15,
  writtenWeight: 30,
  deadline: '',
};

/** 后端 LocalDateTime → datetime-local 输入框值 */
function toInputDateTime(value: string | null): string {
  if (!value) return '';
  return value.slice(0, 16);
}

/** datetime-local 输入框值 → 后端 LocalDateTime（YYYY-MM-DDTHH:mm:ss） */
function toApiDateTime(value: string): string | null {
  if (!value) return null;
  return value.length === 16 ? `${value}:00` : value;
}

function toOptionalNumber(value: string): number | null {
  if (value.trim() === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function fromJob(job: JobPosition): FormState {
  return {
    title: job.title,
    department: job.department ?? '',
    location: job.location ?? '',
    employmentType: job.employmentType,
    headcount: job.headcount != null ? String(job.headcount) : '',
    salaryRange: job.salaryRange ?? '',
    jdText: job.jdText,
    categories: job.categories ?? [],
    skillId: job.skillId,
    difficulty: job.difficulty,
    questionCount: job.questionCount,
    interviewMode: job.interviewMode,
    knowledgeBaseId: job.knowledgeBaseId != null ? String(job.knowledgeBaseId) : '',
    timeLimitMinutes: job.timeLimitMinutes != null ? String(job.timeLimitMinutes) : '',
    llmProvider: job.llmProvider ?? '',
    screeningEnabled: job.screeningEnabled,
    screeningThreshold: job.screeningThreshold != null ? String(job.screeningThreshold) : '60',
    aiPassThreshold: job.aiPassThreshold,
    proctorAwayReminder: job.proctorAwayReminder ?? true,
    proctorWatermark: job.proctorWatermark ?? true,
    writtenTestEnabled: job.writtenTestEnabled ?? false,
    writtenSingleCount: job.writtenSingleCount ?? 5,
    writtenMultiCount: job.writtenMultiCount ?? 3,
    writtenFillCount: job.writtenFillCount ?? 2,
    writtenTimeLimitMinutes: job.writtenTimeLimitMinutes ?? 15,
    writtenWeight: job.writtenWeight ?? 30,
    deadline: toInputDateTime(job.deadline),
  };
}

function toRequest(form: FormState): JobPositionRequest {
  return {
    title: form.title.trim(),
    department: form.department.trim() || null,
    location: form.location.trim() || null,
    employmentType: form.employmentType,
    headcount: toOptionalNumber(form.headcount),
    salaryRange: form.salaryRange.trim() || null,
    jdText: form.jdText,
    categories: form.categories,
    skillId: form.skillId,
    difficulty: form.difficulty,
    questionCount: form.questionCount,
    interviewMode: form.interviewMode,
    knowledgeBaseId: toOptionalNumber(form.knowledgeBaseId),
    timeLimitMinutes: toOptionalNumber(form.timeLimitMinutes),
    llmProvider: form.llmProvider || null,
    screeningEnabled: form.screeningEnabled,
    screeningThreshold: form.screeningEnabled ? toOptionalNumber(form.screeningThreshold) : null,
    aiPassThreshold: form.aiPassThreshold,
    proctorAwayReminder: form.proctorAwayReminder,
    proctorWatermark: form.proctorWatermark,
    writtenTestEnabled: form.writtenTestEnabled,
    writtenSingleCount: form.writtenSingleCount,
    writtenMultiCount: form.writtenMultiCount,
    writtenFillCount: form.writtenFillCount,
    writtenTimeLimitMinutes: form.writtenTimeLimitMinutes,
    writtenWeight: form.writtenWeight,
    deadline: toApiDateTime(form.deadline),
  };
}

const labelClass = 'mb-1.5 block text-sm font-medium text-slate-700 dark:text-slate-300';
const inputClass = 'dark-input w-full rounded-lg px-3 py-2 text-sm outline-none';
const hintClass = 'mt-1 text-xs text-slate-400 dark:text-slate-500';

export default function JobEditPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const navigate = useNavigate();
  const editingId = jobId ? Number(jobId) : null;
  const isEdit = editingId !== null && !Number.isNaN(editingId);
  const isAdmin = getStoredUser()?.role === 'ADMIN';

  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [status, setStatus] = useState<JobStatus>('DRAFT');
  const [loading, setLoading] = useState(isEdit);
  const [saving, setSaving] = useState(false);
  const [parsing, setParsing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [skills, setSkills] = useState<SkillDTO[]>([]);
  const [knowledgeBases, setKnowledgeBases] = useState<KnowledgeBaseItem[]>([]);
  const [providers, setProviders] = useState<ProviderItem[]>([]);

  useEffect(() => {
    skillApi.listSkills().then(setSkills).catch(() => setSkills([]));
    knowledgeBaseApi.getAllKnowledgeBases().then(setKnowledgeBases).catch(() => setKnowledgeBases([]));
    // 模型提供商列表仅管理员可读，其他角色使用默认模型
    if (isAdmin) {
      llmProviderApi.list().then(setProviders).catch(() => setProviders([]));
    }
  }, [isAdmin]);

  useEffect(() => {
    if (!isEdit || editingId === null) return;
    let cancelled = false;
    setLoading(true);
    jobApi.get(editingId)
      .then(job => {
        if (cancelled) return;
        setForm(fromJob(job));
        setStatus(job.status);
      })
      .catch(err => !cancelled && setError(getErrorMessage(err)))
      .finally(() => !cancelled && setLoading(false));
    return () => {
      cancelled = true;
    };
  }, [isEdit, editingId]);

  const update = <K extends keyof FormState>(key: K, value: FormState[K]) => {
    setForm(prev => ({ ...prev, [key]: value }));
  };

  const selectedSkill = useMemo(
    () => skills.find(s => s.id === form.skillId) ?? null,
    [skills, form.skillId],
  );

  const handleParseJd = async () => {
    if (form.jdText.trim().length < 50) {
      setError('请先填写完整的职位描述（至少 50 字）再解析');
      return;
    }
    setParsing(true);
    setError(null);
    try {
      const categories = await jobApi.parseJd(form.jdText);
      setForm(prev => ({ ...prev, categories, skillId: CUSTOM_SKILL_ID }));
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setParsing(false);
    }
  };

  const removeCategory = (key: string) => {
    update('categories', form.categories.filter(c => c.key !== key));
  };

  const cyclePriority = (key: string) => {
    update('categories', form.categories.map(c => {
      if (c.key !== key) return c;
      const next = PRIORITY_ORDER[(PRIORITY_ORDER.indexOf(c.priority) + 1) % PRIORITY_ORDER.length];
      return { ...c, priority: next };
    }));
  };

  const validate = (publish: boolean): string | null => {
    if (!form.title.trim()) return '请填写岗位名称';
    if (!form.jdText.trim()) return '请填写职位描述（JD）';
    if (form.screeningEnabled && toOptionalNumber(form.screeningThreshold) === null) {
      return '启用简历初筛时需要填写初筛分数线';
    }
    if (form.writtenTestEnabled) {
      const totalWritten = form.writtenSingleCount + form.writtenMultiCount + form.writtenFillCount;
      if (totalWritten < 1 || totalWritten > 30) {
        return '启用在线笔试时，单选 + 多选 + 填空总题数需在 1 ~ 30 题之间';
      }
    }
    if (publish && form.skillId === CUSTOM_SKILL_ID && form.categories.length === 0) {
      return '按 JD 出题的岗位发布前需要先解析 JD 生成考察维度';
    }
    return null;
  };

  const handleSave = async (publish: boolean) => {
    const message = validate(publish);
    if (message) {
      setError(message);
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const payload = toRequest(form);
      const saved = isEdit && editingId !== null
        ? await jobApi.update(editingId, payload)
        : await jobApi.create(payload);
      if (publish && saved.status !== 'OPEN') {
        await jobApi.changeStatus(saved.id, 'OPEN');
      }
      navigate(ROUTES.adminJobs);
    } catch (err) {
      setError(getErrorMessage(err));
    } finally {
      setSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex justify-center py-20">
        <Loader2 className="h-8 w-8 animate-spin text-primary-500" />
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-4xl space-y-6 pb-16">
      <div className="flex items-center gap-3">
        <Link to={ROUTES.adminJobs} className="rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-600 dark:hover:bg-slate-800">
          <ArrowLeft className="h-5 w-5" />
        </Link>
        <div>
          <h1 className="text-2xl font-bold text-slate-900 dark:text-white">{isEdit ? '编辑岗位' : '新建岗位'}</h1>
          {isEdit && (
            <p className="mt-0.5 text-sm text-slate-500 dark:text-slate-400">当前状态：{JOB_STATUS_LABELS[status]}</p>
          )}
        </div>
      </div>

      {error && (
        <div className="rounded-xl bg-red-50 px-4 py-2 text-sm text-red-600 dark:bg-red-900/20 dark:text-red-400">{error}</div>
      )}

      {/* 基础信息 */}
      <section className="dark-card space-y-4 p-6">
        <h2 className="text-base font-semibold text-slate-900 dark:text-white">基础信息</h2>
        <div className="grid gap-4 md:grid-cols-2">
          <div className="md:col-span-2">
            <label className={labelClass}>岗位名称 *</label>
            <input className={inputClass} value={form.title} maxLength={128} onChange={e => update('title', e.target.value)} placeholder="如：Java 后端开发工程师" />
          </div>
          <div>
            <label className={labelClass}>所属部门</label>
            <input className={inputClass} value={form.department} maxLength={128} onChange={e => update('department', e.target.value)} />
          </div>
          <div>
            <label className={labelClass}>工作地点</label>
            <input className={inputClass} value={form.location} maxLength={128} onChange={e => update('location', e.target.value)} />
          </div>
          <div>
            <label className={labelClass}>用工类型</label>
            <select className={inputClass} value={form.employmentType} onChange={e => update('employmentType', e.target.value as EmploymentType)}>
              {Object.entries(EMPLOYMENT_TYPE_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </div>
          <div>
            <label className={labelClass}>招聘人数</label>
            <input className={inputClass} type="number" min={1} max={9999} value={form.headcount} onChange={e => update('headcount', e.target.value)} />
          </div>
          <div>
            <label className={labelClass}>薪资范围</label>
            <input className={inputClass} value={form.salaryRange} maxLength={64} onChange={e => update('salaryRange', e.target.value)} placeholder="如：20-35K·14薪" />
          </div>
          <div>
            <label className={labelClass}>投递截止时间</label>
            <input className={inputClass} type="datetime-local" value={form.deadline} onChange={e => update('deadline', e.target.value)} />
            <p className={hintClass}>留空表示长期有效</p>
          </div>
        </div>
      </section>

      {/* 职位描述与考察维度 */}
      <section className="dark-card space-y-4 p-6">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-slate-900 dark:text-white">职位描述（JD）*</h2>
          <button
            type="button"
            onClick={handleParseJd}
            disabled={parsing}
            className="btn-secondary flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm disabled:opacity-60"
          >
            {parsing ? <Loader2 className="h-4 w-4 animate-spin" /> : <Sparkles className="h-4 w-4" />}
            {parsing ? '解析中…' : 'AI 解析考察维度'}
          </button>
        </div>
        <textarea
          className={`${inputClass} min-h-[220px] font-mono`}
          value={form.jdText}
          maxLength={20000}
          onChange={e => update('jdText', e.target.value)}
          placeholder="支持 Markdown，将展示在招聘门户，并作为 AI 出题与评估依据"
        />
        <div>
          <p className={labelClass}>考察维度</p>
          {form.categories.length === 0 ? (
            <p className="text-sm text-slate-400 dark:text-slate-500">暂无维度，点击「AI 解析考察维度」从 JD 自动生成</p>
          ) : (
            <div className="flex flex-wrap gap-2">
              {form.categories.map(c => (
                <span key={c.key} className="flex items-center gap-1.5 rounded-full bg-primary-50 py-1 pl-3 pr-1.5 text-sm text-primary-700 dark:bg-primary-900/30 dark:text-primary-300">
                  {c.label}
                  <button
                    type="button"
                    onClick={() => cyclePriority(c.key)}
                    className="rounded-full bg-white/70 px-2 text-xs dark:bg-slate-800/70"
                    title="点击切换优先级"
                  >
                    {PRIORITY_LABELS[c.priority]}
                  </button>
                  <button type="button" onClick={() => removeCategory(c.key)} className="rounded-full p-0.5 hover:bg-primary-100 dark:hover:bg-primary-900/60" title="移除">
                    <X className="h-3.5 w-3.5" />
                  </button>
                </span>
              ))}
            </div>
          )}
        </div>
      </section>

      {/* 在线笔试配置 */}
      <section className="dark-card space-y-4 p-6">
        <div>
          <h2 className="text-base font-semibold text-slate-900 dark:text-white">在线笔试（选择题 / 填空题）</h2>
          <p className={hintClass}>
            独立环节置于 AI 面试之前（网页作答），AI 按岗位 JD 为每位候选人实时出题并打乱选项；客观题自动判分，填空题规则匹配 + AI 语义判分。
          </p>
        </div>
        <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
          <input
            type="checkbox"
            checked={form.writtenTestEnabled}
            onChange={e => update('writtenTestEnabled', e.target.checked)}
            className="h-4 w-4 accent-primary-500"
            data-testid="written-enabled-toggle"
          />
          启用在线笔试（文字面试与语音面试均适用）
        </label>
        {form.writtenTestEnabled && (
          <div className="grid gap-4 md:grid-cols-3">
            <div>
              <label className={labelClass}>单选题数量（0-20）</label>
              <input
                className={inputClass}
                type="number"
                min={0}
                max={20}
                value={form.writtenSingleCount}
                onChange={e => update('writtenSingleCount', Math.max(0, Math.min(20, Number(e.target.value) || 0)))}
                data-testid="written-single-count"
              />
            </div>
            <div>
              <label className={labelClass}>多选题数量（0-20）</label>
              <input
                className={inputClass}
                type="number"
                min={0}
                max={20}
                value={form.writtenMultiCount}
                onChange={e => update('writtenMultiCount', Math.max(0, Math.min(20, Number(e.target.value) || 0)))}
                data-testid="written-multi-count"
              />
            </div>
            <div>
              <label className={labelClass}>填空题数量（0-20）</label>
              <input
                className={inputClass}
                type="number"
                min={0}
                max={20}
                value={form.writtenFillCount}
                onChange={e => update('writtenFillCount', Math.max(0, Math.min(20, Number(e.target.value) || 0)))}
                data-testid="written-fill-count"
              />
            </div>
            <div>
              <label className={labelClass}>笔试限时（分钟，5-120）</label>
              <input
                className={inputClass}
                type="number"
                min={5}
                max={120}
                value={form.writtenTimeLimitMinutes}
                onChange={e => update('writtenTimeLimitMinutes', Math.max(5, Math.min(120, Number(e.target.value) || 15)))}
                data-testid="written-time-limit"
              />
            </div>
            <div className="md:col-span-2">
              <label className={labelClass}>
                综合分权重：笔试 {form.writtenWeight}% + AI 面试 {100 - form.writtenWeight}%
              </label>
              <input
                type="range"
                min={0}
                max={100}
                step={5}
                value={form.writtenWeight}
                onChange={e => update('writtenWeight', Number(e.target.value))}
                className="w-full accent-primary-500"
                data-testid="written-weight"
              />
              <p className={hintClass}>
                共 {form.writtenSingleCount + form.writtenMultiCount + form.writtenFillCount} 题（上限 30 题）；最终得分 = 笔试分 × {form.writtenWeight}% + 面试分 × {100 - form.writtenWeight}%
              </p>
            </div>
          </div>
        )}
      </section>

      {/* 在线面试配置 */}
      <section className="dark-card space-y-4 p-6">
        <h2 className="text-base font-semibold text-slate-900 dark:text-white">在线 AI 面试</h2>
        <div className="grid gap-4 md:grid-cols-2">
          <div>
            <label className={labelClass}>面试主题</label>
            <select className={inputClass} value={form.skillId} onChange={e => update('skillId', e.target.value)}>
              <option value={CUSTOM_SKILL_ID}>按 JD 考察维度出题（推荐）</option>
              {skills.filter(s => s.id !== CUSTOM_SKILL_ID).map(s => (
                <option key={s.id} value={s.id}>{s.name}</option>
              ))}
            </select>
            {selectedSkill && <p className={hintClass}>{selectedSkill.description}</p>}
          </div>
          <div>
            <label className={labelClass}>面试形式</label>
            <select className={inputClass} value={form.interviewMode} onChange={e => update('interviewMode', e.target.value as InterviewMode)}>
              {Object.entries(INTERVIEW_MODE_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
          </div>
          <div>
            <label className={labelClass}>默认定位 / 建议难度</label>
            <select className={inputClass} value={form.difficulty} onChange={e => update('difficulty', e.target.value as JobDifficulty)}>
              {Object.entries(JOB_DIFFICULTY_LABELS).map(([value, label]) => (
                <option key={value} value={value}>{label}</option>
              ))}
            </select>
            <p className={hintClass}>候选人投递或开考前可自主选择初级 / 中级 / 高级，AI 将按候选人自选定位出题</p>
          </div>
          <div>
            <label className={labelClass}>题目数量：{form.questionCount} 题</label>
            <input
              type="range"
              min={3}
              max={20}
              value={form.questionCount}
              onChange={e => update('questionCount', Number(e.target.value))}
              className="w-full accent-primary-500"
            />
          </div>
          <div>
            <label className={labelClass}>面试时长上限（分钟）</label>
            <input className={inputClass} type="number" min={5} max={180} value={form.timeLimitMinutes} onChange={e => update('timeLimitMinutes', e.target.value)} />
            <p className={hintClass}>留空表示不限时</p>
          </div>
          <div>
            <label className={labelClass}>题库（可选）</label>
            <select className={inputClass} value={form.knowledgeBaseId} onChange={e => update('knowledgeBaseId', e.target.value)}>
              <option value="">不使用题库，AI 实时出题</option>
              {knowledgeBases.map(kb => (
                <option key={kb.id} value={kb.id}>{kb.name}（{kb.questionCount} 题）</option>
              ))}
            </select>
          </div>
          {isAdmin && (
            <div>
              <label className={labelClass}>模型提供商（可选）</label>
              <select className={inputClass} value={form.llmProvider} onChange={e => update('llmProvider', e.target.value)}>
                <option value="">使用系统默认</option>
                {providers.map(p => (
                  <option key={p.id} value={p.id}>{p.id}（{p.model}）</option>
                ))}
              </select>
            </div>
          )}
        </div>
      </section>

      {/* AI 审核规则 */}
      <section className="dark-card space-y-4 p-6">
        <h2 className="text-base font-semibold text-slate-900 dark:text-white">AI 审核规则</h2>
        <div className="grid gap-4 md:grid-cols-2">
          <div className="md:col-span-2">
            <label className="flex items-center gap-2 text-sm text-slate-700 dark:text-slate-300">
              <input
                type="checkbox"
                checked={form.screeningEnabled}
                onChange={e => update('screeningEnabled', e.target.checked)}
                className="h-4 w-4 accent-primary-500"
              />
              启用 AI 简历初筛（低于分数线的投递直接判为未通过并发送结果通知邮件）
            </label>
          </div>
          {form.screeningEnabled && (
            <div>
              <label className={labelClass}>简历初筛分数线（0-100）</label>
              <input className={inputClass} type="number" min={0} max={100} value={form.screeningThreshold} onChange={e => update('screeningThreshold', e.target.value)} />
            </div>
          )}
          <div>
            <label className={labelClass}>AI 面试通过分数线：{form.aiPassThreshold} 分</label>
            <input
              type="range"
              min={0}
              max={100}
              value={form.aiPassThreshold}
              onChange={e => update('aiPassThreshold', Number(e.target.value))}
              className="w-full accent-primary-500"
            />
            <p className={hintClass}>通过 AI 审核的候选人进入 HR 待审核；AI 判定不通过将直接结束流程并发送通知邮件</p>
          </div>
        </div>
      </section>

      {/* 防作弊（低打扰） */}
      <section className="dark-card space-y-4 p-6">
        <div>
          <h2 className="text-base font-semibold text-slate-900 dark:text-white">防作弊（低打扰）</h2>
          <p className={hintClass}>
            系统始终在后台无感记录切屏时长、粘贴、作答节奏等行为并给出风险评分，仅供 HR 参考，不会自动淘汰；不开启摄像头、不强制全屏。
          </p>
        </div>
        <div className="space-y-3">
          <label className="flex items-start gap-2 text-sm text-slate-700 dark:text-slate-300">
            <input
              type="checkbox"
              checked={form.proctorAwayReminder}
              onChange={e => update('proctorAwayReminder', e.target.checked)}
              className="mt-0.5 h-4 w-4 accent-primary-500"
              data-testid="proctor-away-reminder"
            />
            <span>
              离开页面提醒
              <span className="block text-xs text-slate-400">
                候选人离开页面超过 30 秒再返回时，显示一条可自动消失的轻提示（每场最多 2 次）
              </span>
            </span>
          </label>
          <label className="flex items-start gap-2 text-sm text-slate-700 dark:text-slate-300">
            <input
              type="checkbox"
              checked={form.proctorWatermark}
              onChange={e => update('proctorWatermark', e.target.checked)}
              className="mt-0.5 h-4 w-4 accent-primary-500"
              data-testid="proctor-watermark-toggle"
            />
            <span>
              页面淡水印
              <span className="block text-xs text-slate-400">
                在面试页面叠加极淡的“姓名 · 投递编号”水印，降低截图外传题目的风险，不影响作答
              </span>
            </span>
          </label>
        </div>
      </section>

      <div className="flex justify-end gap-3">
        <Link to={ROUTES.adminJobs} className="btn-secondary rounded-xl px-5 py-2 text-sm">取消</Link>
        <button
          type="button"
          onClick={() => handleSave(false)}
          disabled={saving}
          className="btn-secondary flex items-center gap-2 rounded-xl px-5 py-2 text-sm disabled:opacity-60"
        >
          {saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
          保存
        </button>
        {status !== 'OPEN' && (
          <button
            type="button"
            onClick={() => handleSave(true)}
            disabled={saving}
            className="btn-primary flex items-center gap-2 rounded-xl px-5 py-2 text-sm disabled:opacity-60"
          >
            <Send className="h-4 w-4" />
            保存并发布
          </button>
        )}
      </div>
    </div>
  );
}
