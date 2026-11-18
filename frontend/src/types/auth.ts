export type AdminRole = 'ADMIN' | 'HR' | 'INTERVIEWER';

export interface AdminUser {
  id: number;
  username: string;
  displayName: string | null;
  email: string | null;
  role: AdminRole;
  enabled: boolean;
  lastLoginAt: string | null;
  createdAt: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
  user: AdminUser;
}

export interface ChangePasswordRequest {
  oldPassword: string;
  newPassword: string;
}

export interface CreateAdminUserRequest {
  username: string;
  password: string;
  displayName?: string;
  email?: string;
  role: AdminRole;
}

export interface UpdateAdminUserRequest {
  displayName?: string;
  email?: string;
  role: AdminRole;
  enabled: boolean;
}

export const ADMIN_ROLE_LABELS: Record<AdminRole, string> = {
  ADMIN: '管理员',
  HR: '招聘专员',
  INTERVIEWER: '面试官',
};
