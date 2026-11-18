import request from './request';
import type {
  ApplicationDetail,
  ApplicationListItem,
  ApplicationStats,
  ApplicationStatus,
  BatchReviewResult,
  OfflineInterviewRequest,
  PageResult,
} from '../types/recruitment';

export interface ApplicationListParams {
  jobId?: number;
  statuses?: ApplicationStatus[];
  keyword?: string;
  page?: number;
  size?: number;
}

export const applicationApi = {
  list(params: ApplicationListParams = {}): Promise<PageResult<ApplicationListItem>> {
    const query = new URLSearchParams();
    if (params.jobId !== undefined) {
      query.set('jobId', String(params.jobId));
    }
    if (params.statuses && params.statuses.length > 0) {
      params.statuses.forEach(s => query.append('statuses', s));
    }
    if (params.keyword) {
      query.set('keyword', params.keyword);
    }
    query.set('page', String(params.page ?? 1));
    query.set('size', String(params.size ?? 20));
    return request.get(`/api/admin/applications?${query.toString()}`);
  },

  stats(jobId?: number): Promise<ApplicationStats> {
    const query = jobId !== undefined ? `?jobId=${jobId}` : '';
    return request.get(`/api/admin/applications/stats${query}`);
  },

  detail(id: number): Promise<ApplicationDetail> {
    return request.get(`/api/admin/applications/${id}`);
  },

  downloadResume(id: number): Promise<Blob> {
    return request.download(`/api/admin/applications/${id}/resume/file`);
  },

  approve(id: number, comment?: string): Promise<ApplicationDetail> {
    return request.post(`/api/admin/applications/${id}/approve`, comment ? { comment } : {});
  },

  reject(id: number, comment?: string): Promise<ApplicationDetail> {
    return request.post(`/api/admin/applications/${id}/reject`, comment ? { comment } : {});
  },

  batchReview(ids: number[], approve: boolean, comment?: string): Promise<BatchReviewResult> {
    return request.post('/api/admin/applications/batch-review', { ids, approve, comment });
  },

  scheduleOffline(id: number, payload: OfflineInterviewRequest): Promise<ApplicationDetail> {
    return request.post(`/api/admin/applications/${id}/offline-interview`, payload);
  },
};
