-- ============================================================================
-- 1. 防作弊事件表记录候选人实际粘贴的完整文本内容（而非仅统计粘贴次数/字数）
-- 2. 投递单新增候选人答题前选填的 AI 相关经验与个人特长补充
-- ============================================================================

ALTER TABLE interview_proctor_events
    ADD COLUMN IF NOT EXISTS pasted_text TEXT;

ALTER TABLE applications
    ADD COLUMN IF NOT EXISTS ai_experience VARCHAR(32),
    ADD COLUMN IF NOT EXISTS specialties TEXT;
