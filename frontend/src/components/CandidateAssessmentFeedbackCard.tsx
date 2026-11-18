import { useState } from 'react';
import { BookOpen, ChevronDown, ChevronUp, FileQuestion, MessageSquareWarning, XCircle } from 'lucide-react';
import {
  WRITTEN_QUESTION_TYPE_LABELS,
  type CandidateAssessmentFeedback,
} from '../types/recruitment';

const LETTERS = ['A', 'B', 'C', 'D', 'E', 'F'];

interface CandidateAssessmentFeedbackCardProps {
  applicationId: number;
  feedback: CandidateAssessmentFeedback;
}

export default function CandidateAssessmentFeedbackCard({
  applicationId,
  feedback,
}: CandidateAssessmentFeedbackCardProps) {
  const totalFailedQuestions =
    feedback.failedWrittenQuestions.length + feedback.failedInterviewQuestions.length;
  const [expanded, setExpanded] = useState(true);

  return (
    <div
      data-testid={`assessment-feedback-${applicationId}`}
      className="space-y-4 rounded-2xl border border-rose-200/80 bg-rose-50/40 p-5 text-left text-sm dark:border-rose-800/60 dark:bg-rose-950/20"
    >
      {/* 1. 未通过原因 */}
      <div className="space-y-2.5">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <div className="flex items-center gap-2 font-semibold text-rose-900 dark:text-rose-200">
            <XCircle className="h-4 w-4 shrink-0 text-rose-600 dark:text-rose-400" />
            <span>{feedback.rejectStageLabel || '评估未通过原因'}</span>
          </div>
          {feedback.scoreSummary && (
            <span
              data-testid={`assessment-score-summary-${applicationId}`}
              className="rounded-full bg-white/90 px-3 py-0.5 text-xs font-medium text-slate-700 ring-1 ring-rose-200/70 dark:bg-slate-900/70 dark:text-slate-200 dark:ring-rose-800/60"
            >
              {feedback.scoreSummary}
            </span>
          )}
        </div>

        <p
          data-testid={`assessment-summary-${applicationId}`}
          className="leading-relaxed text-slate-700 dark:text-slate-200"
        >
          {feedback.summary}
        </p>

        {feedback.reasons.length > 0 && (
          <div className="space-y-1.5 pt-0.5">
            <div className="text-xs font-semibold text-slate-700 dark:text-slate-300">
              主要薄弱项与未通过原因：
            </div>
            <ul
              data-testid={`assessment-reasons-${applicationId}`}
              className="list-inside list-disc space-y-1 text-xs leading-relaxed text-slate-600 dark:text-slate-300"
            >
              {feedback.reasons.map((item, idx) => (
                <li key={idx}>{item}</li>
              ))}
            </ul>
          </div>
        )}
      </div>

      {/* 2. 没通过的题目解析 */}
      {totalFailedQuestions > 0 && (
        <div
          data-testid={`failed-questions-section-${applicationId}`}
          className="space-y-3 border-t border-rose-200/70 pt-3.5 dark:border-rose-800/50"
        >
          <button
            type="button"
            data-testid={`toggle-failed-questions-${applicationId}`}
            onClick={() => setExpanded(prev => !prev)}
            className="flex w-full items-center justify-between rounded-xl bg-white/80 px-3.5 py-2 text-xs font-semibold text-slate-800 transition hover:bg-white dark:bg-slate-900/60 dark:text-slate-100 dark:hover:bg-slate-900"
          >
            <span className="flex items-center gap-1.5">
              <BookOpen className="h-4 w-4 text-primary-500" />
              未通过题目解析（共 {totalFailedQuestions} 题
              {feedback.failedWrittenQuestions.length > 0
                ? ` · 笔试错题 ${feedback.failedWrittenQuestions.length} 道`
                : ''}
              {feedback.failedInterviewQuestions.length > 0
                ? ` · 面试薄弱题 ${feedback.failedInterviewQuestions.length} 道`
                : ''}
              ）
            </span>
            <span className="flex items-center gap-1 text-primary-600 dark:text-primary-400">
              {expanded ? '收起解析' : '展开解析'}
              {expanded ? <ChevronUp className="h-3.5 w-3.5" /> : <ChevronDown className="h-3.5 w-3.5" />}
            </span>
          </button>

          {expanded && (
            <div className="space-y-4">
              {/* 2.1 在线笔试未通过题目 */}
              {feedback.failedWrittenQuestions.length > 0 && (
                <div className="space-y-2.5" data-testid={`failed-written-list-${applicationId}`}>
                  <div className="flex items-center gap-1.5 text-xs font-semibold text-slate-700 dark:text-slate-200">
                    <FileQuestion className="h-3.5 w-3.5 text-amber-600 dark:text-amber-400" />
                    在线笔试未通过题目（{feedback.failedWrittenQuestions.length} 题）
                  </div>
                  <div className="space-y-3">
                    {feedback.failedWrittenQuestions.map(q => (
                      <div
                        key={q.index}
                        data-testid={`failed-written-item-${applicationId}-${q.index}`}
                        className="space-y-2.5 rounded-xl border border-slate-200/90 bg-white p-4 text-xs dark:border-slate-700/80 dark:bg-slate-900/70"
                      >
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="font-semibold text-slate-900 dark:text-white">
                            笔试第 {q.index + 1} 题
                          </span>
                          <span className="rounded-full bg-slate-100 px-2 py-0.5 text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                            {WRITTEN_QUESTION_TYPE_LABELS[q.type]}
                          </span>
                          {q.category && <span className="text-slate-400">· {q.category}</span>}
                          <span className="rounded-full bg-rose-50 px-2 py-0.5 font-medium text-rose-700 dark:bg-rose-900/30 dark:text-rose-300">
                            未答对
                          </span>
                        </div>

                        <div className="whitespace-pre-wrap text-sm font-medium leading-relaxed text-slate-900 dark:text-slate-100">
                          {q.question}
                        </div>

                        {q.options.length > 0 && (
                          <div className="grid gap-1.5 pt-0.5">
                            {q.options.map((opt, idx) => {
                              const letter = LETTERS[idx] ?? String(idx + 1);
                              const isCorrect = q.correctAnswers.includes(letter);
                              const isChosen = q.candidateAnswer.includes(letter);
                              return (
                                <div
                                  key={letter}
                                  className={`flex items-start gap-2 rounded-lg border px-3 py-1.5 ${
                                    isCorrect
                                      ? 'border-emerald-300 bg-emerald-50/70 text-emerald-900 dark:border-emerald-700/60 dark:bg-emerald-900/25 dark:text-emerald-200'
                                      : isChosen
                                        ? 'border-rose-300 bg-rose-50/70 text-rose-900 dark:border-rose-700/60 dark:bg-rose-900/25 dark:text-rose-200'
                                        : 'border-slate-100 text-slate-600 dark:border-slate-800 dark:text-slate-300'
                                  }`}
                                >
                                  <span className="font-semibold">{letter}.</span>
                                  <span className="flex-1">{opt}</span>
                                  {isCorrect && (
                                    <span className="shrink-0 font-semibold text-emerald-700 dark:text-emerald-300">
                                      标准选项
                                    </span>
                                  )}
                                  {!isCorrect && isChosen && (
                                    <span className="shrink-0 font-semibold text-rose-600 dark:text-rose-300">
                                      您的选择
                                    </span>
                                  )}
                                </div>
                              );
                            })}
                          </div>
                        )}

                        <div className="flex flex-wrap gap-x-5 gap-y-1 pt-0.5 text-xs">
                          <div>
                            <span className="text-slate-500 dark:text-slate-400">您的作答：</span>
                            <span className="font-semibold text-rose-600 dark:text-rose-300">
                              {q.candidateAnswer.length > 0 ? q.candidateAnswer.join('、') : '（未作答）'}
                            </span>
                          </div>
                          <div>
                            <span className="text-slate-500 dark:text-slate-400">标准答案：</span>
                            <span className="font-semibold text-emerald-700 dark:text-emerald-300">
                              {q.correctAnswers.join(' / ')}
                            </span>
                          </div>
                        </div>

                        {(q.explanation || q.judgeReason) && (
                          <div className="space-y-1 rounded-lg bg-slate-50 p-3 leading-relaxed text-slate-700 dark:bg-slate-800/70 dark:text-slate-200">
                            {q.judgeReason && (
                              <div>
                                <span className="font-semibold text-amber-700 dark:text-amber-300">判分说明：</span>
                                {q.judgeReason}
                              </div>
                            )}
                            {q.explanation && (
                              <div>
                                <span className="font-semibold text-primary-700 dark:text-primary-300">题目解析：</span>
                                {q.explanation}
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {/* 2.2 在线面试未达标题目 */}
              {feedback.failedInterviewQuestions.length > 0 && (
                <div className="space-y-2.5" data-testid={`failed-interview-list-${applicationId}`}>
                  <div className="flex items-center gap-1.5 text-xs font-semibold text-slate-700 dark:text-slate-200">
                    <MessageSquareWarning className="h-3.5 w-3.5 text-rose-600 dark:text-rose-400" />
                    在线面试未达标 / 薄弱题目解析（{feedback.failedInterviewQuestions.length} 题）
                  </div>
                  <div className="space-y-3">
                    {feedback.failedInterviewQuestions.map(item => (
                      <div
                        key={item.questionIndex}
                        data-testid={`failed-interview-item-${applicationId}-${item.questionIndex}`}
                        className="space-y-2.5 rounded-xl border border-slate-200/90 bg-white p-4 text-xs dark:border-slate-700/80 dark:bg-slate-900/70"
                      >
                        <div className="flex flex-wrap items-center justify-between gap-2">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-semibold text-slate-900 dark:text-white">
                              面试第 {item.questionIndex + 1} 题
                            </span>
                            {item.category && (
                              <span className="rounded-full bg-slate-100 px-2 py-0.5 text-slate-600 dark:bg-slate-800 dark:text-slate-300">
                                {item.category}
                              </span>
                            )}
                          </div>
                          {item.score != null && (
                            <span className="rounded-full bg-rose-50 px-2.5 py-0.5 font-medium text-rose-700 dark:bg-rose-900/30 dark:text-rose-300">
                              本题得分：{item.score} 分（达标线 {item.passThreshold} 分）
                            </span>
                          )}
                        </div>

                        <div className="whitespace-pre-wrap text-sm font-medium leading-relaxed text-slate-900 dark:text-slate-100">
                          {item.question}
                        </div>

                        <div className="rounded-lg bg-slate-50 p-2.5 leading-relaxed text-slate-600 dark:bg-slate-800/60 dark:text-slate-300">
                          <span className="font-semibold text-slate-700 dark:text-slate-200">您的回答：</span>
                          <span className="whitespace-pre-wrap">{item.userAnswer}</span>
                        </div>

                        {item.feedback && (
                          <div className="rounded-lg bg-amber-50/70 p-2.5 leading-relaxed text-amber-950 dark:bg-amber-900/20 dark:text-amber-100">
                            <span className="font-semibold text-amber-800 dark:text-amber-200">失分原因与点评：</span>
                            <span className="whitespace-pre-wrap">{item.feedback}</span>
                          </div>
                        )}

                        {item.keyPoints.length > 0 && (
                          <div className="space-y-1">
                            <div className="font-semibold text-slate-700 dark:text-slate-200">核心考察要点：</div>
                            <div className="flex flex-wrap gap-1.5">
                              {item.keyPoints.map((kp, i) => (
                                <span
                                  key={i}
                                  className="rounded-md bg-primary-50 px-2 py-0.5 text-primary-700 dark:bg-primary-900/30 dark:text-primary-300"
                                >
                                  {kp}
                                </span>
                              ))}
                            </div>
                          </div>
                        )}

                        {item.referenceAnswer && (
                          <div className="rounded-lg bg-emerald-50/70 p-3 leading-relaxed text-emerald-950 dark:bg-emerald-900/20 dark:text-emerald-100">
                            <div className="mb-1 font-semibold text-emerald-800 dark:text-emerald-200">
                              参考解析：
                            </div>
                            <div className="whitespace-pre-wrap">{item.referenceAnswer}</div>
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
