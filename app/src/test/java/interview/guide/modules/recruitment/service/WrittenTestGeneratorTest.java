package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;

import interview.guide.modules.recruitment.model.CandidateWrittenQuestionDTO;
import interview.guide.modules.recruitment.model.GeneratedWrittenTest;
import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionType;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AI 笔试出题规范化测试")
class WrittenTestGeneratorTest {

  @Test
  @DisplayName("过滤不合规题目、去除选项字母前缀、打乱选项后准确重映射答案字母，并按单选→多选→填空重排题号")
  void normalizesFiltersAndShufflesQuestionsDeterministically() {
    GeneratedWrittenTest raw = new GeneratedWrittenTest(List.of(
        // 合法单选（带前缀，答案给字母 B）
        new GeneratedWrittenTest.Item("SINGLE_CHOICE", "MySQL InnoDB 默认隔离级别是？",
            List.of("A. READ UNCOMMITTED", "B. REPEATABLE READ", "C. READ COMMITTED", "D. SERIALIZABLE"),
            List.of("B"), "InnoDB 默认可重复读", "MySQL"),
        // 非法单选：给了 2 个正确答案 → 丢弃
        new GeneratedWrittenTest.Item("SINGLE_CHOICE", "非法单选题",
            List.of("选项1", "选项2", "选项3"), List.of("A", "B"), "", "Java"),
        // 合法多选（答案给选项原文 + 字母）
        new GeneratedWrittenTest.Item("MULTIPLE_CHOICE", "以下属于 JVM 垃圾收集器的是？",
            List.of("A. G1", "B. ZGC", "C. Epoll", "D. Shenandoah"),
            List.of("G1", "B", "D"), "Epoll 是 Linux I/O 多路复用", "JVM"),
        // 非法多选：只有 1 个正确答案 → 丢弃
        new GeneratedWrittenTest.Item("MULTIPLE_CHOICE", "非法多选题",
            List.of("一", "二", "三", "四"), List.of("A"), "", "JVM"),
        // 合法填空：全角括号转换为统一空位标记
        new GeneratedWrittenTest.Item("FILL_BLANK", "HTTP 协议中表示资源未找到的状态码是（  ）",
            List.of(), List.of("404", "404 Not Found"), "404 表示 Not Found", "网络"),
        // 非法填空：多空题 → 丢弃
        new GeneratedWrittenTest.Item("FILL_BLANK", "TCP 通过 ____ 握手建立连接、____ 挥手断开连接",
            List.of(), List.of("三次"), "", "网络"),
        // 非法填空：题干直接包含答案 → 丢弃
        new GeneratedWrittenTest.Item("FILL_BLANK", "Redis 持久化 RDB 的全称缩写是 ____",
            List.of(), List.of("RDB"), "", "Redis")
    ));

    List<WrittenQuestion> questions = WrittenTestGenerator.normalize(raw, 2, 2, 2, new Random(42L));

    assertThat(questions).hasSize(3);
    assertThat(questions).extracting(WrittenQuestion::index).containsExactly(0, 1, 2);
    assertThat(questions).extracting(WrittenQuestion::type).containsExactly(
        WrittenQuestionType.SINGLE_CHOICE,
        WrittenQuestionType.MULTIPLE_CHOICE,
        WrittenQuestionType.FILL_BLANK);

    // 验证单选题打乱后，答案字母对应原正确选项 "REPEATABLE READ"
    WrittenQuestion single = questions.get(0);
    assertThat(single.options()).noneMatch(o -> o.startsWith("A.") || o.startsWith("B."));
    assertThat(single.answers()).hasSize(1);
    int singleIdx = single.answers().get(0).charAt(0) - 'A';
    assertThat(single.options().get(singleIdx)).isEqualTo("REPEATABLE READ");

    // 验证多选题打乱后，3 个答案字母严格对应 G1 / ZGC / Shenandoah 且按字母升序排列
    WrittenQuestion multi = questions.get(1);
    assertThat(multi.answers()).hasSize(3).isSorted();
    List<String> mappedMulti = multi.answers().stream()
        .map(letter -> multi.options().get(letter.charAt(0) - 'A'))
        .toList();
    assertThat(mappedMulti).containsExactlyInAnyOrder("G1", "ZGC", "Shenandoah");

    // 验证填空题空位标记已规范化
    WrittenQuestion fill = questions.get(2);
    assertThat(fill.question()).isEqualTo("HTTP 协议中表示资源未找到的状态码是____");
    assertThat(fill.answers()).containsExactly("404", "404 Not Found");

    // 候选人端 DTO 严禁包含标准答案与解析
    CandidateWrittenQuestionDTO dto = CandidateWrittenQuestionDTO.of(single);
    List<String> fields = Arrays.stream(CandidateWrittenQuestionDTO.class.getRecordComponents())
        .map(RecordComponent::getName)
        .toList();
    assertThat(fields).containsExactly("index", "type", "question", "options", "category")
        .doesNotContain("answers", "explanation");
    assertThat(dto.question()).isEqualTo(single.question());
  }
}
