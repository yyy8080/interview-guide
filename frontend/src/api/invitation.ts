import request from './request';
import type {
  BatchInvitationResult,
  BatchResumeImageExtractResult,
  CreateInvitationsRequest,
  InvitationListItem,
  InvitationParticipationFilter,
  InvitationStats,
  PageResult,
} from '../types/recruitment';

export interface InvitationListParams {
  jobId?: number;
  participation?: InvitationParticipationFilter;
  keyword?: string;
  page?: number;
  size?: number;
}

export const invitationApi = {
  list(params: InvitationListParams = {}): Promise<PageResult<InvitationListItem>> {
    const query = new URLSearchParams();
    if (params.jobId !== undefined) {
      query.set('jobId', String(params.jobId));
    }
    if (params.participation) {
      query.set('participation', params.participation);
    }
    if (params.keyword) {
      query.set('keyword', params.keyword);
    }
    query.set('page', String(params.page ?? 1));
    query.set('size', String(params.size ?? 20));
    return request.get(`/api/admin/invitations?${query.toString()}`);
  },

  stats(jobId?: number): Promise<InvitationStats> {
    const query = jobId !== undefined ? `?jobId=${jobId}` : '';
    return request.get(`/api/admin/invitations/stats${query}`);
  },

  parseResumeImages(files: File[]): Promise<BatchResumeImageExtractResult> {
    const formData = new FormData();
    files.forEach(file => formData.append('files', file));
    return request.upload('/api/admin/invitations/parse-resume-images', formData);
  },

  send(payload: CreateInvitationsRequest): Promise<BatchInvitationResult> {
    return request.post('/api/admin/invitations', payload);
  },

  resend(id: number): Promise<InvitationListItem> {
    return request.post(`/api/admin/invitations/${id}/resend`);
  },

  revoke(id: number): Promise<InvitationListItem> {
    return request.post(`/api/admin/invitations/${id}/revoke`);
  },
};
