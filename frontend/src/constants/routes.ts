export const ROUTES = {
  interview: '/interview',
  interviewCreate: (requestId: string) => `/interview/create/${requestId}`,
  interviewSession: (sessionId: string) => `/interview/session/${sessionId}`,
  resumeUpload: '/upload',
  resumeHistory: '/history',
  knowledgebaseUpload: '/knowledgebase/upload',

  // ========== HR 管理后台 ==========
  adminLogin: '/admin/login',
  adminDashboard: '/admin/dashboard',
  adminJobs: '/admin/jobs',
  adminJobCreate: '/admin/jobs/new',
  adminJobEdit: (id: number) => `/admin/jobs/${id}/edit`,
  adminApplications: '/admin/applications',
  adminApplicationDetail: (id: number) => `/admin/applications/${id}`,
  adminInvitations: '/admin/invitations',
  adminMail: '/admin/mail',
  adminAccount: '/admin/account',

  // ========== 候选人门户 ==========
  careers: '/careers',
  careerLogin: '/careers/login',
  careerMe: '/careers/me',
  careerJob: (id: number) => `/careers/${id}`,
  careerInterview: (applicationId: number) => `/careers/applications/${applicationId}/interview`,
} as const;

export const ROUTE_PATTERNS = {
  interviewCreate: 'interview/create/:requestId',
  interviewSession: 'interview/session/:activeSessionId',
  adminJobEdit: 'admin/jobs/:jobId/edit',
  adminApplicationDetail: 'admin/applications/:applicationId',
  careerJob: 'careers/:jobId',
  careerInterview: 'applications/:applicationId/interview',
} as const;

/** 后台登录后的默认首页 */
export const ADMIN_HOME_PATH = ROUTES.adminApplications;
