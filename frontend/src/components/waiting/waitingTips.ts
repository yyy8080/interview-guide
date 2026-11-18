import { useEffect, useState } from 'react';

/** 通用等待小贴士：作答技巧 + 放松提示（避免与业务页面中的固定文案重复） */
export const GENERAL_WAITING_TIPS: string[] = [
  '作答时先给出结论，再分点展开原因与细节，逻辑会更清晰。',
  '介绍项目时可按「背景 → 职责 → 方案 → 量化结果」的顺序组织回答。',
  '遇到不确定的问题，坦诚说明已知部分与思考过程，比含糊作答更加分。',
  '结合本人真实经历作答，AI 面试官会围绕简历细节进行追问核验。',
  '生成期间无需刷新页面，完成后会自动进入下一步。',
  '深呼吸放松一下，保持专注与平常心是正常发挥的关键。',
  '使用手机作答时，建议关闭其他应用通知并保持屏幕常亮。',
  '请保持网络稳定，笔试作答进度会自动保存。',
];

/** 题目生成阶段专属提示 */
export const QUESTION_GENERATION_TIPS: string[] = [
  'AI 正在结合岗位 JD 与您选择的定位定制专属题目。',
  '作答倒计时将在题目生成完毕后才开始，等待时间不计入作答时长。',
  ...GENERAL_WAITING_TIPS,
];

/** 评估 / 初筛阶段专属提示 */
export const EVALUATION_TIPS: string[] = [
  'AI 正在逐项对照岗位要求进行评估，请耐心等待。',
  '结果出来后会同步更新到「我的投递」，并通过邮件通知您。',
  '您可以离开本页面，稍后回来查看结果，不影响评估进度。',
  ...GENERAL_WAITING_TIPS,
];

/** 面试官思考阶段（答题计时中，仅展示简短提示） */
export const THINKING_TIPS: string[] = [
  'AI 面试官正在分析您的回答…',
  '正在结合您的回答准备下一个问题…',
  '稍等片刻，下一题马上就来…',
];

/**
 * 按固定间隔轮播提示文案
 */
export function useRotatingTip(tips: string[], intervalMs = 6000, active = true): string {
  const [index, setIndex] = useState(() => (tips.length > 0 ? Math.floor(Math.random() * tips.length) : 0));

  useEffect(() => {
    if (!active || tips.length <= 1) return;
    const timer = window.setInterval(() => {
      setIndex(prev => (prev + 1) % tips.length);
    }, intervalMs);
    return () => window.clearInterval(timer);
  }, [active, intervalMs, tips.length]);

  return tips.length > 0 ? tips[index % tips.length] : '';
}
