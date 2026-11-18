package interview.guide.modules.recruitment.model;

import java.util.List;

/**
 * LLM 生成的笔试题（结构化输出，入库前会严格校验与规范化）
 */
public record GeneratedWrittenTest(List<Item> questions) {

  /**
   * @param type        SINGLE_CHOICE / MULTIPLE_CHOICE / FILL_BLANK
   * @param question    题干；填空题用 ____ 表示空位
   * @param options     选项文本（选择题 4 个，多选题可 4-5 个），不带 A/B/C 前缀
   * @param answers     选择题为正确选项字母；填空题为所有可接受的标准答案
   * @param explanation 简短解析
   * @param category    考察知识点
   */
  public record Item(
      String type,
      String question,
      List<String> options,
      List<String> answers,
      String explanation,
      String category
  ) {
  }
}
