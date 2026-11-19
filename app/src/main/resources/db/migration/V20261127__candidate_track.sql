-- 候选人投递简历时选择的技术方向偏好（FRONTEND 偏向前端 / BACKEND 偏向后端），
-- 后续 AI 简历初筛、在线笔试出题、文字/语音面试出题及综合决策均按该方向进行，答题时无需重复选择
ALTER TABLE applications ADD COLUMN IF NOT EXISTS candidate_track VARCHAR(16);
