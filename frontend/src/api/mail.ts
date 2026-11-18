import request from './request';
import type {
  EmailLogItem,
  EmailLogStatus,
  EmailTemplateItem,
  EmailTemplateRequest,
  MailSettings,
  MailSettingsRequest,
  PageResult,
  WeComEmailAccount,
} from '../types/recruitment';

export const mailApi = {
  getSettings(): Promise<MailSettings> {
    return request.get('/api/admin/mail/settings');
  },

  updateSettings(payload: MailSettingsRequest): Promise<MailSettings> {
    return request.put('/api/admin/mail/settings', payload);
  },

  queryWeComAccount(): Promise<WeComEmailAccount> {
    return request.get('/api/admin/mail/wecom/account');
  },

  sendTest(to: string): Promise<void> {
    return request.post('/api/admin/mail/test', { to });
  },

  listTemplates(): Promise<EmailTemplateItem[]> {
    return request.get('/api/admin/mail/templates');
  },

  updateTemplate(code: string, payload: EmailTemplateRequest): Promise<EmailTemplateItem> {
    return request.put(`/api/admin/mail/templates/${encodeURIComponent(code)}`, payload);
  },

  resetTemplate(code: string): Promise<EmailTemplateItem> {
    return request.delete(`/api/admin/mail/templates/${encodeURIComponent(code)}`);
  },

  listLogs(params: { status?: EmailLogStatus; page?: number; size?: number } = {}): Promise<PageResult<EmailLogItem>> {
    const query = new URLSearchParams();
    if (params.status) {
      query.set('status', params.status);
    }
    query.set('page', String(params.page ?? 1));
    query.set('size', String(params.size ?? 20));
    return request.get(`/api/admin/mail/logs?${query.toString()}`);
  },

  resendLog(id: number): Promise<void> {
    return request.post(`/api/admin/mail/logs/${id}/resend`);
  },
};
