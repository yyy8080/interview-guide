import { request } from './request';
import type {
  AdminUser,
  ChangePasswordRequest,
  CreateAdminUserRequest,
  LoginRequest,
  LoginResponse,
  UpdateAdminUserRequest,
} from '../types/auth';

export const authApi = {
  login(data: LoginRequest): Promise<LoginResponse> {
    return request.post<LoginResponse>('/api/admin/auth/login', data);
  },

  me(): Promise<AdminUser> {
    return request.get<AdminUser>('/api/admin/auth/me');
  },

  /** 修改本人密码，返回新令牌（旧令牌失效） */
  changePassword(data: ChangePasswordRequest): Promise<LoginResponse> {
    return request.put<LoginResponse>('/api/admin/auth/password', data);
  },
};

export const adminUserApi = {
  list(): Promise<AdminUser[]> {
    return request.get<AdminUser[]>('/api/admin/users');
  },

  create(data: CreateAdminUserRequest): Promise<AdminUser> {
    return request.post<AdminUser>('/api/admin/users', data);
  },

  update(id: number, data: UpdateAdminUserRequest): Promise<AdminUser> {
    return request.put<AdminUser>(`/api/admin/users/${id}`, data);
  },

  resetPassword(id: number, newPassword: string): Promise<void> {
    return request.put<void>(`/api/admin/users/${id}/password`, { newPassword });
  },
};
