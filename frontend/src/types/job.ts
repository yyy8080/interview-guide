import type { CategoryDTO } from '../api/skill';

export type JobStatus = 'DRAFT' | 'OPEN' | 'CLOSED';
export type InterviewMode = 'TEXT' | 'VOICE' | 'BOTH';
export type ConcreteInterviewMode = 'TEXT' | 'VOICE';
export type EmploymentType = 'FULL_TIME' | 'PART_TIME' | 'INTERN' | 'CONTRACT';
export type JobDifficulty = 'junior' | 'mid' | 'senior';

export interface JobPosition {
  id: number;
  title: string;
  department: string | null;
  location: string | null;
  employmentType: EmploymentType;
  headcount: number | null;
  salaryRange: string | null;
  jdText: string;
  categories: CategoryDTO[];
  skillId: string;
  difficulty: JobDifficulty;
  questionCount: number;
  interviewMode: InterviewMode;
  knowledgeBaseId: number | null;
  timeLimitMinutes: number | null;
  llmProvider: string | null;
  screeningEnabled: boolean;
  screeningThreshold: number | null;
  aiPassThreshold: number;
  status: JobStatus;
  deadline: string | null;
  publishedAt: string | null;
  createdBy: number | null;
  createdAt: string;
  updatedAt: string;
}

export interface JobPositionRequest {
  title: string;
  department?: string | null;
  location?: string | null;
  employmentType: EmploymentType;
  headcount?: number | null;
  salaryRange?: string | null;
  jdText: string;
  categories?: CategoryDTO[];
  skillId: string;
  difficulty: JobDifficulty;
  questionCount: number;
  interviewMode: InterviewMode;
  knowledgeBaseId?: number | null;
  timeLimitMinutes?: number | null;
  llmProvider?: string | null;
  screeningEnabled: boolean;
  screeningThreshold?: number | null;
  aiPassThreshold: number;
  deadline?: string | null;
}

export interface PublicJob {
  id: number;
  title: string;
  department: string | null;
  location: string | null;
  employmentType: EmploymentType;
  headcount: number | null;
  salaryRange: string | null;
  interviewMode: InterviewMode;
  deadline: string | null;
  publishedAt: string | null;
}

export interface PublicJobDetail extends PublicJob {
  jdText: string;
  questionCount: number;
  timeLimitMinutes: number | null;
}

export const JOB_STATUS_LABELS: Record<JobStatus, string> = {
  DRAFT: '草稿',
  OPEN: '招聘中',
  CLOSED: '已关闭',
};

export const INTERVIEW_MODE_LABELS: Record<InterviewMode, string> = {
  BOTH: '候选人自选（文字 / 语音）',
  TEXT: '文字面试',
  VOICE: '语音面试',
};

export const EMPLOYMENT_TYPE_LABELS: Record<EmploymentType, string> = {
  FULL_TIME: '全职',
  PART_TIME: '兼职',
  INTERN: '实习',
  CONTRACT: '合同工',
};

export const JOB_DIFFICULTY_LABELS: Record<JobDifficulty, string> = {
  junior: '初级',
  mid: '中级',
  senior: '高级',
};
