package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * 笔试题目（含标准答案，仅存储与后台可见）
 *
 * @param index       题号（从 0 开始）
 * @param type        题型
 * @param question    题干；填空题用 ____ 表示空位
 * @param options     选项文本（选择题，按 A、B、C… 顺序）；填空题为空列表
 * @param answers     选择题为正确选项字母（如 ["A", "C"]）；填空题为可接受的标准答案
 * @param explanation 解析
 * @param category    考察知识点
 */
public record WrittenQuestion(
    int index,
    WrittenQuestionType type,
    String question,
    List<String> options,
    List<String> answers,
    String explanation,
    String category
) {
}
