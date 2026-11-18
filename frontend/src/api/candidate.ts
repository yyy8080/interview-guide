import request from './request';
import type { ConcreteInterviewMode } from '../types/job';
import type {
  CandidateAnswerResponse,
  CandidateApplication,
  CandidateInterviewState,
  CandidateLoginResponse,
  CandidateProfile,
  ProctorEventType,
} from '../types/recruitment';

export const candidateAuthApi = {
  sendCode(email: string): Promise<void> {
    return request.post('/api/public/candidate-auth/send-code', { email });
  },

  verify(email: string, code: string): Promise<CandidateLoginResponse> {
    return request.post('/api/public/candidate-auth/verify', { email, code });
  },
};

export const candidateApi = {
  me(): Promise<CandidateProfile> {
    return request.get('/api/candidate/me');
  },

  apply(params: {
    jobId: number;
    file: File;
    name: string;
    phone?: string;
    consent: boolean;
  }): Promise<CandidateApplication> {
    const formData = new FormData();
    formData.append('file', params.file);
    formData.append('name', params.name);
    if (params.phone) {
      formData.append('phone', params.phone);
    }
    formData.append('consent', String(params.consent));
    return request.upload(`/api/candidate/jobs/${params.jobId}/apply`, formData);
  },

  listApplications(): Promise<CandidateApplication[]> {
    return request.get('/api/candidate/applications');
  },

  getApplication(id: number): Promise<CandidateApplication> {
    return request.get(`/api/candidate/applications/${id}`);
  },

  startInterview(applicationId: number, mode: ConcreteInterviewMode): Promise<CandidateInterviewState> {
    return request.post(
      `/api/candidate/applications/${applicationId}/interview/start`,
      { mode },
      { timeout: 180000 },
    );
  },

  getInterview(applicationId: number): Promise<CandidateInterviewState> {
    return request.get(`/api/candidate/applications/${applicationId}/interview`);
  },

  submitAnswer(
    applicationId: number,
    questionIndex: number,
    answer: string,
  ): Promise<CandidateAnswerResponse> {
    return request.post(`/api/candidate/applications/${applicationId}/interview/answer`, {
      questionIndex,
      answer,
    });
  },

  completeInterview(applicationId: number): Promise<CandidateInterviewState> {
    return request.post(`/api/candidate/applications/${applicationId}/interview/complete`);
  },

  endVoiceInterview(applicationId: number): Promise<CandidateInterviewState> {
    return request.post(`/api/candidate/applications/${applicationId}/interview/voice/end`);
  },

  recordProctor(applicationId: number, type: ProctorEventType, detail?: string): Promise<void> {
    return request.post(`/api/candidate/applications/${applicationId}/proctor-events`, { type, detail });
  },

  confirmOffline(applicationId: number): Promise<CandidateApplication> {
    return request.post(`/api/candidate/applications/${applicationId}/offline/confirm`);
  },

  requestReschedule(applicationId: number, note: string): Promise<CandidateApplication> {
    return request.post(`/api/candidate/applications/${applicationId}/offline/reschedule`, { note });
  },
};
