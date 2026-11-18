import { request } from './request';
import type { CategoryDTO } from './skill';
import type {
  JobPosition,
  JobPositionRequest,
  JobStatus,
  PublicJob,
  PublicJobDetail,
} from '../types/job';

/** 后台岗位管理 */
export const jobApi = {
  list(params?: { status?: JobStatus; keyword?: string }): Promise<JobPosition[]> {
    return request.get<JobPosition[]>('/api/admin/jobs', { params });
  },

  get(id: number): Promise<JobPosition> {
    return request.get<JobPosition>(`/api/admin/jobs/${id}`);
  },

  create(data: JobPositionRequest): Promise<JobPosition> {
    return request.post<JobPosition>('/api/admin/jobs', data);
  },

  update(id: number, data: JobPositionRequest): Promise<JobPosition> {
    return request.put<JobPosition>(`/api/admin/jobs/${id}`, data);
  },

  changeStatus(id: number, status: JobStatus): Promise<JobPosition> {
    return request.put<JobPosition>(`/api/admin/jobs/${id}/status`, { status });
  },

  copy(id: number): Promise<JobPosition> {
    return request.post<JobPosition>(`/api/admin/jobs/${id}/copy`);
  },

  delete(id: number): Promise<void> {
    return request.delete<void>(`/api/admin/jobs/${id}`);
  },

  parseJd(jdText: string): Promise<CategoryDTO[]> {
    return request.post<CategoryDTO[]>('/api/admin/jobs/parse-jd', { jdText }, { timeout: 120000 });
  },
};

/** 候选人门户公开岗位 */
export const publicJobApi = {
  list(): Promise<PublicJob[]> {
    return request.get<PublicJob[]>('/api/public/jobs');
  },

  get(id: number): Promise<PublicJobDetail> {
    return request.get<PublicJobDetail>(`/api/public/jobs/${id}`);
  },
};
