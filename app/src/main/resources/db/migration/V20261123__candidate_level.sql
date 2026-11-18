-- 候选人自选应聘定位（junior 初级 / mid 中级 / senior 高级），用于 AI 按定位针对性出题与综合评估
ALTER TABLE applications ADD COLUMN IF NOT EXISTS candidate_level VARCHAR(16);
