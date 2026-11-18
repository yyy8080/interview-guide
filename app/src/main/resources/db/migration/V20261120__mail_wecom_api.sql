-- 邮件模块扩展：支持企业微信邮件 API（/cgi-bin/exmail/app/compose_send）与 SMTP 双发信通道

ALTER TABLE mail_settings ADD COLUMN IF NOT EXISTS channel VARCHAR(32) NOT NULL DEFAULT 'SMTP';
ALTER TABLE mail_settings ADD COLUMN IF NOT EXISTS wecom_corp_id VARCHAR(128);
ALTER TABLE mail_settings ADD COLUMN IF NOT EXISTS wecom_secret_ciphertext TEXT;
ALTER TABLE mail_settings ADD COLUMN IF NOT EXISTS wecom_secret_nonce VARCHAR(64);
ALTER TABLE mail_settings ADD COLUMN IF NOT EXISTS wecom_api_base_url VARCHAR(512);

ALTER TABLE mail_settings DROP CONSTRAINT IF EXISTS ck_mail_settings_channel;
ALTER TABLE mail_settings ADD CONSTRAINT ck_mail_settings_channel
  CHECK (channel IN ('SMTP', 'WECOM_API'));
