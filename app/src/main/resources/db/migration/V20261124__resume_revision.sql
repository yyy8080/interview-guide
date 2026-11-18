-- 简历初筛未通过后允许候选人修改重传 1 次简历
ALTER TABLE applications
  ADD COLUMN IF NOT EXISTS resume_revision_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN applications.resume_revision_count IS '候选人修改重传简历次数（默认最多允许 1 次初筛重提）';
