import request from './request';
import type { ConcreteInterviewMode, JobDifficulty } from '../types/job';
import type {
  AiExperienceLevel,
  CandidateAnswerResponse,
  CandidateApplication,
  CandidateInterviewState,
  CandidateJobInvitationStatus,
  CandidateLoginResponse,
  CandidateProfile,
  CandidateTrack,
  CandidateWrittenTest,
  AnswerTelemetry,
  ProctorEventPayload,
} from '../types/recruitment';

export interface CandidatePreTestProfilePayload {
  aiExperience?: AiExperienceLevel | null;
  specialties?: string | null;
}

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

  getJobInvitation(jobId: number): Promise<CandidateJobInvitationStatus> {
    return request.get(`/api/candidate/jobs/${jobId}/invitation`);
  },

  apply(params: {
    jobId: number;
    file: File;
    name: string;
    phone?: string;
    candidateLevel?: JobDifficulty;
    candidateTrack?: CandidateTrack | null;
    consent: boolean;
  }): Promise<CandidateApplication> {
    const formData = new FormData();
    formData.append('file', params.file);
    formData.append('name', params.name);
    if (params.phone) {
      formData.append('phone', params.phone);
    }
    if (params.candidateLevel) {
      formData.append('candidateLevel', params.candidateLevel);
    }
    if (params.candidateTrack) {
      formData.append('candidateTrack', params.candidateTrack);
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

  resubmitResume(
    applicationId: number,
    params: { file: File; candidateLevel?: JobDifficulty; candidateTrack?: CandidateTrack | null },
  ): Promise<CandidateApplication> {
    const formData = new FormData();
    formData.append('file', params.file);
    if (params.candidateLevel) {
      formData.append('candidateLevel', params.candidateLevel);
    }
    if (params.candidateTrack) {
      formData.append('candidateTrack', params.candidateTrack);
    }
    return request.upload(`/api/candidate/applications/${applicationId}/resubmit-resume`, formData);
  },

  getWrittenTest(applicationId: number): Promise<CandidateWrittenTest> {
    return request.get(`/api/candidate/applications/${applicationId}/written-test`);
  },

  startWrittenTest(
    applicationId: number,
    candidateLevel?: JobDifficulty,
    profile?: CandidatePreTestProfilePayload,
  ): Promise<CandidateWrittenTest> {
    const body: Record<string, unknown> = {};
    if (candidateLevel) body.candidateLevel = candidateLevel;
    if (profile?.aiExperience) body.aiExperience = profile.aiExperience;
    if (profile?.specialties && profile.specialties.trim()) {
      body.specialties = profile.specialties.trim();
    }
    return request.post(
      `/api/candidate/applications/${applicationId}/written-test/start`,
      body,
      { timeout: 180000 },
    );
  },

  saveWrittenAnswers(applicationId: number, answers: Record<number, string[]>): Promise<void> {
    return request.put(`/api/candidate/applications/${applicationId}/written-test/answers`, {
      answers,
    });
  },

  submitWrittenTest(
    applicationId: number,
    answers: Record<number, string[]>,
  ): Promise<CandidateWrittenTest> {
    return request.post(
      `/api/candidate/applications/${applicationId}/written-test/submit`,
      { answers },
      { timeout: 120000 },
    );
  },

  startInterview(
    applicationId: number,
    mode: ConcreteInterviewMode,
    candidateLevel?: JobDifficulty,
    profile?: CandidatePreTestProfilePayload,
  ): Promise<CandidateInterviewState> {
    const body: Record<string, unknown> = { mode };
    if (candidateLevel) body.candidateLevel = candidateLevel;
    if (profile?.aiExperience) body.aiExperience = profile.aiExperience;
    if (profile?.specialties && profile.specialties.trim()) {
      body.specialties = profile.specialties.trim();
    }
    return request.post(
      `/api/candidate/applications/${applicationId}/interview/start`,
      body,
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
    telemetry?: AnswerTelemetry,
  ): Promise<CandidateAnswerResponse> {
    return request.post(`/api/candidate/applications/${applicationId}/interview/answer`, {
      questionIndex,
      answer,
      telemetry,
    });
  },

  completeInterview(applicationId: number): Promise<CandidateInterviewState> {
    return request.post(`/api/candidate/applications/${applicationId}/interview/complete`);
  },

  endVoiceInterview(applicationId: number): Promise<CandidateInterviewState> {
    return request.post(`/api/candidate/applications/${applicationId}/interview/voice/end`);
  },

  recordProctor(applicationId: number, event: ProctorEventPayload): Promise<void> {
    return request.post(`/api/candidate/applications/${applicationId}/proctor-events`, event);
  },

  confirmOffline(applicationId: number): Promise<CandidateApplication> {
    return request.post(`/api/candidate/applications/${applicationId}/offline/confirm`);
  },

  requestReschedule(applicationId: number, note: string): Promise<CandidateApplication> {
    return request.post(`/api/candidate/applications/${applicationId}/offline/reschedule`, { note });
  },
};
