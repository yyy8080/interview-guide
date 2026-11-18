import type { ConcreteInterviewMode, InterviewMode, JobDifficulty } from './job';

export interface PageResult<T> {
  items: T[];
  total: number;
  page: number;
  size: number;
}

// ========== 候选人认证与门户 ==========

export interface CandidateProfile {
  id: number;
  email: string;
  name: string | null;
  phone: string | null;
}

export interface CandidateLoginResponse {
  token: string;
  expiresAt: string;
  candidate: CandidateProfile;
}

export type CandidateStage =
  | 'SCREENING'
  | 'INTERVIEW_PENDING'
  | 'INTERVIEWING'
  | 'UNDER_REVIEW'
  | 'PASSED'
  | 'OFFLINE_INVITED'
  | 'OFFLINE_CONFIRMED'
  | 'REJECTED'
  | 'EXPIRED';

export const CANDIDATE_STAGE_LABELS: Record<CandidateStage, string> = {
  SCREENING: '简历筛选中',
  INTERVIEW_PENDING: '待完成在线面试',
  INTERVIEWING: '在线面试进行中',
  UNDER_REVIEW: '面试审核中',
  PASSED: '审核通过，待安排线下面试',
  OFFLINE_INVITED: '已邀约线下面试',
  OFFLINE_CONFIRMED: '已确认线下面试',
  REJECTED: '未通过',
  EXPIRED: '面试邀请已过期',
};

export interface CandidateOfflineInterview {
  interviewTime: string;
  durationMinutes: number | null;
  location: string | null;
  interviewer: string | null;
  notes: string | null;
  confirmStatus: 'PENDING' | 'CONFIRMED' | 'RESCHEDULE_REQUESTED' | null;
  candidateNote: string | null;
}

export interface CandidateScreeningFeedback {
  summary: string;
  missingRequirements: string[];
  canResubmit: boolean;
  revisionCount: number;
  maxRevisions: number;
}

export interface CandidateApplication {
  id: number;
  jobId: number;
  jobTitle: string;
  department: string | null;
  location: string | null;
  candidateLevel?: JobDifficulty;
  stage: CandidateStage;
  availableModes: ConcreteInterviewMode[];
  interviewMode: ConcreteInterviewMode | null;
  questionCount: number | null;
  timeLimitMinutes: number | null;
  submittedAt: string;
  inviteExpiresAt: string | null;
  interviewStartedAt: string | null;
  interviewDeadlineAt: string | null;
  interviewCompletedAt: string | null;
  offlineInterview: CandidateOfflineInterview | null;
  screeningFeedback?: CandidateScreeningFeedback | null;
}

export interface CandidateQuestion {
  questionIndex: number;
  question: string;
  category: string | null;
  followUp: boolean;
}

export type WrittenQuestionType = 'SINGLE_CHOICE' | 'MULTIPLE_CHOICE' | 'FILL_BLANK';
export type WrittenTestStatus = 'IN_PROGRESS' | 'SUBMITTED' | 'GRADED';

export const WRITTEN_QUESTION_TYPE_LABELS: Record<WrittenQuestionType, string> = {
  SINGLE_CHOICE: '单选题',
  MULTIPLE_CHOICE: '多选题',
  FILL_BLANK: '填空题',
};

export const WRITTEN_TEST_STATUS_LABELS: Record<WrittenTestStatus, string> = {
  IN_PROGRESS: '作答中',
  SUBMITTED: '已交卷待判分',
  GRADED: '已判分',
};

export interface CandidateWrittenQuestion {
  index: number;
  type: WrittenQuestionType;
  question: string;
  options: string[];
  category: string | null;
}

export interface CandidateWrittenTest {
  applicationId: number;
  candidateLevel?: JobDifficulty;
  status: WrittenTestStatus | null;
  totalQuestions: number;
  questions: CandidateWrittenQuestion[];
  answers: Record<string, string[]>;
  startedAt: string | null;
  deadlineAt: string | null;
  remainingSeconds: number | null;
  watermarkText: string | null;
  proctorAwayReminder: boolean;
}

export interface CandidateInterviewState {
  applicationId: number;
  jobTitle: string;
  candidateLevel?: JobDifficulty;
  mode: ConcreteInterviewMode | null;
  stage: CandidateStage;
  startedAt: string | null;
  deadlineAt: string | null;
  remainingSeconds: number | null;
  totalQuestions: number;
  currentQuestionIndex: number;
  currentQuestion: CandidateQuestion | null;
  finished: boolean;
  voiceSessionId: number | null;
  voiceWsPath: string | null;
  plannedDurationMinutes: number | null;
  /** 长时间离开页面回来后是否给出柔和提醒 */
  proctorAwayReminder?: boolean;
  /** 页面水印文字，岗位关闭水印时为空 */
  watermarkText?: string | null;
  /** 是否需要或存在在线笔试 */
  writtenTestEnabled?: boolean;
  writtenTestStatus?: WrittenTestStatus | null;
  writtenQuestionCount?: number;
  writtenTimeLimitMinutes?: number;
}

/** 文字面试单题作答遥测（无感采集） */
export interface AnswerTelemetry {
  durationMs: number;
  firstInputMs: number | null;
  typedChars: number;
  pastedChars: number;
  maxBurstChars: number;
  awayMs: number;
}

export interface CandidateAnswerResponse {
  finished: boolean;
  nextQuestion: CandidateQuestion | null;
  currentQuestionIndex: number;
  totalQuestions: number;
}

export type ProctorEventType =
  | 'TAB_HIDDEN'
  | 'WINDOW_BLUR'
  | 'PASTE'
  | 'COPY'
  | 'FULLSCREEN_EXIT'
  | 'VIEWPORT_SHRINK'
  | 'MULTI_SCREEN'
  | 'VOICE_TEXT_EDITED'
  | 'VOICE_SLOW_RESPONSE';

export const PROCTOR_EVENT_LABELS: Record<ProctorEventType, string> = {
  TAB_HIDDEN: '切换标签页',
  WINDOW_BLUR: '窗口失焦',
  PASTE: '粘贴内容',
  COPY: '复制题目',
  FULLSCREEN_EXIT: '退出全屏',
  VIEWPORT_SHRINK: '窗口明显缩小',
  MULTI_SCREEN: '扩展显示器',
  VOICE_TEXT_EDITED: '语音回答大幅改写',
  VOICE_SLOW_RESPONSE: '语音长时间未应答',
};

export interface ProctorEventPayload {
  type: ProctorEventType;
  detail?: string;
  durationMs?: number;
  questionIndex?: number;
  chars?: number;
}

export type ProctorRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH';

export const PROCTOR_RISK_LABELS: Record<ProctorRiskLevel, string> = {
  LOW: '行为正常',
  MEDIUM: '中风险',
  HIGH: '高风险',
};

export const PROCTOR_RISK_BADGES: Record<ProctorRiskLevel, string> = {
  LOW: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300',
  MEDIUM: 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
  HIGH: 'bg-rose-50 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300',
};

export interface ProctorRiskAssessment {
  score: number;
  level: ProctorRiskLevel;
  findings: string[];
  flaggedQuestions: number[];
}

export interface AnswerTelemetryItem {
  questionIndex: number;
  durationMs: number | null;
  firstInputMs: number | null;
  typedChars: number | null;
  pastedChars: number | null;
  maxBurstChars: number | null;
  awayMs: number | null;
  answerChars: number | null;
  pasteRatio: number | null;
  flagged: boolean;
}

// ========== 后台投递管理 ==========

export type ApplicationStatus =
  | 'SUBMITTED'
  | 'SCREENING'
  | 'SCREEN_REJECTED'
  | 'INTERVIEW_INVITED'
  | 'INTERVIEWING'
  | 'AI_REVIEWING'
  | 'AI_PASSED'
  | 'AI_REJECTED'
  | 'HR_APPROVED'
  | 'HR_REJECTED'
  | 'OFFLINE_SCHEDULED'
  | 'OFFLINE_CONFIRMED'
  | 'EXPIRED';

export type AiDecision = 'PASS' | 'REJECT';
export type OperatorType = 'SYSTEM' | 'AI' | 'HR' | 'CANDIDATE';

export const APPLICATION_STATUS_LABELS: Record<ApplicationStatus, string> = {
  SUBMITTED: '已投递',
  SCREENING: 'AI 初筛中',
  SCREEN_REJECTED: '初筛不通过',
  INTERVIEW_INVITED: '已发面试邀请',
  INTERVIEWING: '在线面试中',
  AI_REVIEWING: 'AI 审核中',
  AI_PASSED: '待 HR 审核',
  AI_REJECTED: 'AI 审核不通过',
  HR_APPROVED: 'HR 审核通过',
  HR_REJECTED: 'HR 审核不通过',
  OFFLINE_SCHEDULED: '已邀约线下面试',
  OFFLINE_CONFIRMED: '已确认线下面试',
  EXPIRED: '面试已过期',
};

export const APPLICATION_STATUS_BADGES: Record<ApplicationStatus, string> = {
  SUBMITTED: 'bg-slate-100 text-slate-700 dark:bg-slate-700 dark:text-slate-200',
  SCREENING: 'bg-blue-50 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300',
  SCREEN_REJECTED: 'bg-rose-50 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300',
  INTERVIEW_INVITED: 'bg-indigo-50 text-indigo-700 dark:bg-indigo-900/30 dark:text-indigo-300',
  INTERVIEWING: 'bg-cyan-50 text-cyan-700 dark:bg-cyan-900/30 dark:text-cyan-300',
  AI_REVIEWING: 'bg-violet-50 text-violet-700 dark:bg-violet-900/30 dark:text-violet-300',
  AI_PASSED: 'bg-amber-50 text-amber-700 dark:bg-amber-900/30 dark:text-amber-300',
  AI_REJECTED: 'bg-rose-50 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300',
  HR_APPROVED: 'bg-emerald-50 text-emerald-700 dark:bg-emerald-900/30 dark:text-emerald-300',
  HR_REJECTED: 'bg-rose-50 text-rose-700 dark:bg-rose-900/30 dark:text-rose-300',
  OFFLINE_SCHEDULED: 'bg-teal-50 text-teal-700 dark:bg-teal-900/30 dark:text-teal-300',
  OFFLINE_CONFIRMED: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200',
  EXPIRED: 'bg-slate-100 text-slate-500 dark:bg-slate-800 dark:text-slate-400',
};

export interface ApplicationListItem {
  id: number;
  jobId: number;
  jobTitle: string;
  candidateName: string;
  candidateEmail: string | null;
  candidatePhone: string | null;
  candidateLevel?: JobDifficulty;
  status: ApplicationStatus;
  interviewMode: InterviewMode | null;
  screeningScore: number | null;
  writtenScore?: number | null;
  aiScore: number | null;
  aiDecision: AiDecision | null;
  proctorRiskScore?: number | null;
  proctorRiskLevel?: ProctorRiskLevel | null;
  submittedAt: string;
  updatedAt: string;
}

export interface ScreeningResult {
  score: number;
  summary: string;
  strengths: string[];
  risks: string[];
  matchedRequirements: string[];
  missingRequirements: string[];
}

export interface DecisionResult {
  decision: string;
  confidence: number;
  summary: string;
  reasons: string[];
  risks: string[];
  suggestedQuestions: string[];
}

export interface WrittenTestSummary {
  writtenScore: number;
  weight: number;
  interviewScore: number;
  finalScore: number;
  correctCount: number;
  totalCount: number;
}

export interface WrittenTestItem {
  index: number;
  type: WrittenQuestionType;
  question: string;
  options: string[];
  correctAnswers: string[];
  explanation: string | null;
  category: string | null;
  candidateAnswer: string[];
  correct: boolean | null;
  judgedBy: string | null;
  reason: string | null;
}

export interface WrittenTestDetail {
  status: WrittenTestStatus;
  score: number | null;
  correctCount: number | null;
  totalCount: number;
  weight: number;
  startedAt: string;
  deadlineAt: string;
  submittedAt: string | null;
  gradedAt: string | null;
  items: WrittenTestItem[];
}

export interface AiReviewRecord {
  finalDecision: AiDecision | null;
  interviewScore: number | null;
  passThreshold: number | null;
  llm: DecisionResult | null;
  fallback: boolean;
  note: string | null;
  proctorCounts: Record<string, number>;
  proctorRisk?: ProctorRiskAssessment | null;
  written?: WrittenTestSummary | null;
}

export interface ProctorEventItem {
  id: number;
  type: ProctorEventType;
  detail: string | null;
  durationMs?: number | null;
  questionIndex?: number | null;
  chars?: number | null;
  occurredAt: string;
}

export interface ApplicationStatusLog {
  id: number;
  fromStatus: ApplicationStatus | null;
  toStatus: ApplicationStatus;
  operatorType: OperatorType;
  operatorId: number | null;
  operatorName: string;
  remark: string | null;
  createdAt: string;
}

export interface OfflineInterviewDetail {
  scheduleId: number;
  interviewTime: string;
  durationMinutes: number | null;
  location: string;
  interviewType: string;
  interviewer: string | null;
  roundNumber: number | null;
  notes: string | null;
  confirmStatus: 'PENDING' | 'CONFIRMED' | 'RESCHEDULE_REQUESTED' | null;
  candidateNote: string | null;
  confirmedAt: string | null;
}

export interface OfflineInterviewRequest {
  interviewTime: string;
  durationMinutes: number;
  location: string;
  interviewer?: string | null;
  notes?: string | null;
  roundNumber?: number | null;
}

export interface ApplicationDetail {
  id: number;
  status: ApplicationStatus;
  candidateId: number;
  candidateName: string;
  candidateEmail: string | null;
  candidatePhone: string | null;
  candidateLevel?: JobDifficulty;
  jobId: number;
  jobTitle: string;
  jobDifficulty?: JobDifficulty | null;
  aiPassThreshold: number | null;
  screeningThreshold: number | null;
  resumeId: number;
  resumeFilename: string | null;
  resumeText: string | null;
  resumeRevisionCount?: number;
  screeningScore: number | null;
  screeningResult: ScreeningResult | null;
  writtenTest?: WrittenTestDetail | null;
  interviewMode: ConcreteInterviewMode | null;
  interviewSessionId: string | null;
  voiceSessionId: number | null;
  interviewStartedAt: string | null;
  interviewCompletedAt: string | null;
  aiScore: number | null;
  aiDecision: AiDecision | null;
  aiReview: AiReviewRecord | null;
  taskError: string | null;
  hrReviewerId: number | null;
  hrReviewerName: string | null;
  hrComment: string | null;
  hrReviewedAt: string | null;
  proctorCounts: Partial<Record<ProctorEventType, number>>;
  proctorEvents: ProctorEventItem[];
  proctorRisk?: ProctorRiskAssessment | null;
  answerTelemetry?: AnswerTelemetryItem[];
  logs: ApplicationStatusLog[];
  offlineInterview: OfflineInterviewDetail | null;
  emails: EmailLogItem[];
  submittedAt: string;
  inviteExpiresAt: string | null;
  updatedAt: string;
}

export interface BatchReviewResult {
  succeeded: number[];
  failed: { id: number; message: string }[];
}

export interface FunnelStage {
  key: string;
  label: string;
  count: number;
}

export interface ApplicationStats {
  total: number;
  byStatus: Record<ApplicationStatus, number>;
  funnel: FunnelStage[];
}

// ========== 邮件配置与发件箱 ==========

export type MailChannel = 'SMTP' | 'WECOM_API';
export type MailSecurity = 'NONE' | 'SSL' | 'STARTTLS';
export type EmailLogStatus = 'PENDING' | 'SENDING' | 'SENT' | 'FAILED';

export const MAIL_CHANNEL_LABELS: Record<MailChannel, string> = {
  SMTP: 'SMTP 发件服务器',
  WECOM_API: '企业微信邮件 API',
};

export const MAIL_SECURITY_LABELS: Record<MailSecurity, string> = {
  SSL: 'SSL / TLS（通常 465 端口）',
  STARTTLS: 'STARTTLS（通常 587 端口）',
  NONE: '不加密（仅限本地 Mailpit / 内网）',
};

export const EMAIL_LOG_STATUS_LABELS: Record<EmailLogStatus, string> = {
  PENDING: '待发送',
  SENDING: '发送中',
  SENT: '已发送',
  FAILED: '发送失败',
};

export interface MailSettings {
  enabled: boolean;
  channel: MailChannel;
  host: string | null;
  port: number | null;
  username: string | null;
  passwordSet: boolean;
  security: MailSecurity;
  fromAddress: string | null;
  fromName: string | null;
  wecomCorpId: string | null;
  wecomSecretSet: boolean;
  wecomApiBaseUrl: string | null;
  companyName: string | null;
  portalBaseUrl: string | null;
  contactInfo: string | null;
  updatedAt: string | null;
}

export interface MailSettingsRequest {
  enabled: boolean;
  channel: MailChannel;
  host?: string | null;
  port?: number | null;
  username?: string | null;
  password?: string | null;
  security?: MailSecurity;
  fromAddress?: string | null;
  fromName?: string | null;
  wecomCorpId?: string | null;
  wecomSecret?: string | null;
  wecomApiBaseUrl?: string | null;
  companyName?: string | null;
  portalBaseUrl?: string | null;
  contactInfo?: string | null;
}

export interface WeComEmailAccount {
  email: string;
  aliasList: string[];
}

export interface EmailTemplateItem {
  code: string;
  name: string;
  subject: string;
  body: string;
  enabled: boolean;
  customized: boolean;
  variables: string[];
  updatedAt: string | null;
}

export interface EmailTemplateRequest {
  subject: string;
  body: string;
  enabled: boolean;
}

export interface EmailLogItem {
  id: number;
  applicationId: number | null;
  toAddress: string;
  templateCode: string;
  subject: string;
  status: EmailLogStatus;
  error: string | null;
  retryCount: number;
  sentAt: string | null;
  createdAt: string;
}
