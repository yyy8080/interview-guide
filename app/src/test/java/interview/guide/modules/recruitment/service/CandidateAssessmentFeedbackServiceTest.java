package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.recruitment.model.AiDecision;
import interview.guide.modules.recruitment.model.AiReviewRecord;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateAssessmentFeedbackDTO;
import interview.guide.modules.recruitment.model.DecisionResult;
import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionResult;
import interview.guide.modules.recruitment.model.WrittenQuestionType;
import interview.guide.modules.recruitment.model.WrittenTestEntity;
import interview.guide.modules.recruitment.model.WrittenTestStatus;
import interview.guide.modules.recruitment.model.WrittenTestSummary;
import interview.guide.modules.recruitment.repository.WrittenTestRepository;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluatedAnswer;
import interview.guide.modules.recruitment.service.InterviewResultReader.EvaluatedInterview;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("候选人提交答题后未通过原因与错题解析服务测试")
class CandidateAssessmentFeedbackServiceTest {

  @Mock
  private WrittenTestRepository writtenTestRepository;

  @Mock
  private InterviewResultReader interviewResultReader;

  private ObjectMapper objectMapper;
  private WrittenTestCodec writtenTestCodec;
  private CandidateAssessmentFeedbackService service;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    writtenTestCodec = new WrittenTestCodec(objectMapper);
    service = new CandidateAssessmentFeedbackService(
        writtenTestRepository, writtenTestCodec, interviewResultReader, objectMapper);
  }

  @Test
  @DisplayName("非终态淘汰阶段（答题中/审核中/已通过/初筛未通过）绝不返回标准答案与题目解析")
  void returnsNullForNonAssessmentRejectedStatuses() {
    JobPositionEntity job = new JobPositionEntity();
    job.setAiPassThreshold(70);

    for (ApplicationStatus status : List.of(
        ApplicationStatus.SCREEN_REJECTED,
        ApplicationStatus.INTERVIEW_INVITED,
        ApplicationStatus.INTERVIEWING,
        ApplicationStatus.AI_REVIEWING,
        ApplicationStatus.AI_PASSED,
        ApplicationStatus.HR_APPROVED)) {
      ApplicationEntity app = new ApplicationEntity();
      app.setId(1L);
      app.setStatus(status);
      assertThat(service.buildIfRejected(app, job)).isNull();
    }
  }

  @Test
  @DisplayName("AI 审核未通过时返回得分概览、未通过原因及笔试错题+面试失分题完整解析（过滤已答对题目）")
  void buildsReasonsAndFailedQuestionExplanationsForAiRejected() throws Exception {
    JobPositionEntity job = new JobPositionEntity();
    job.setId(9L);
    job.setAiPassThreshold(70);

    AiReviewRecord aiReview = new AiReviewRecord(
        AiDecision.REJECT,
        52,
        70,
        new DecisionResult(
            "REJECT",
            0.91,
            "候选人对分布式锁原子释放及 MySQL 隔离级别理解存在偏差，暂未达标。",
            List.of("笔试基础题失分较多", "高并发缓存与锁机制原理阐述不完整"),
            List.of("缺乏生产级故障排查细节"),
            List.of()),
        false,
        null,
        Map.of(),
        null,
        new WrittenTestSummary(33, 30, 60, 52, 1, 3));

    ApplicationEntity app = new ApplicationEntity();
    app.setId(501L);
    app.setJobId(9L);
    app.setStatus(ApplicationStatus.AI_REJECTED);
    app.setAiScore(52);
    app.setAiResultJson(objectMapper.writeValueAsString(aiReview));

    List<WrittenQuestion> questions = List.of(
        new WrittenQuestion(0, WrittenQuestionType.SINGLE_CHOICE,
            "MySQL InnoDB 默认事务隔离级别是？",
            List.of("READ COMMITTED", "REPEATABLE READ", "SERIALIZABLE"),
            List.of("B"), "InnoDB 默认采用可重复读（REPEATABLE READ）。", "MySQL"),
        new WrittenQuestion(1, WrittenQuestionType.SINGLE_CHOICE,
            "Redis 单线程事件循环主要基于什么机制？",
            List.of("多线程阻塞 IO", "IO 多路复用", "轮询休眠"),
            List.of("B"), "Redis 基于 epoll/kqueue 等 IO 多路复用处理网络事件。", "Redis"),
        new WrittenQuestion(2, WrittenQuestionType.FILL_BLANK,
            "释放 Redis 分布式锁需使用____脚本保证比较与删除原子性",
            List.of(), List.of("Lua"), "使用 Lua 脚本原子校验锁持有者标识并删除。", "分布式锁")
    );
    List<WrittenQuestionResult> results = List.of(
        new WrittenQuestionResult(0, false, List.of("A"), WrittenQuestionResult.RULE, null),
        new WrittenQuestionResult(1, true, List.of("B"), WrittenQuestionResult.RULE, null),
        new WrittenQuestionResult(2, false, List.of("Shell"), WrittenQuestionResult.AI, "Shell 脚本无法在 Redis 服务端原子执行")
    );

    WrittenTestEntity wt = new WrittenTestEntity();
    wt.setId(88L);
    wt.setApplicationId(501L);
    wt.setStatus(WrittenTestStatus.GRADED);
    wt.setScore(33);
    wt.setCorrectCount(1);
    wt.setTotalCount(3);
    wt.setQuestionsJson(writtenTestCodec.write(questions));
    wt.setAnswersJson(writtenTestCodec.write(Map.of(0, List.of("A"), 1, List.of("B"), 2, List.of("Shell"))));
    wt.setResultsJson(writtenTestCodec.write(results));

    when(writtenTestRepository.findByApplicationId(501L)).thenReturn(Optional.of(wt));
    when(interviewResultReader.readEvaluatedDetail(app)).thenReturn(new EvaluatedInterview(
        60,
        "基础概念尚可，高并发场景深度不足",
        List.of("需补充分布式锁容灾细节"),
        List.of(
            new EvaluatedAnswer(0, "JVM", "请介绍 G1 回收器特点", "Region 分区与可预测停顿模型", 82,
                "回答清晰", "G1 将堆划分为多个 Region...", List.of("Region", "Remembered Set")),
            new EvaluatedAnswer(1, "Redis", "Redisson 看门狗如何续期？", "定时加时间", 45,
                "未说明默认 30s 锁超时与每 10s 后台定时任务检查续期逻辑",
                "加锁未显式指定 leaseTime 时启动 WatchDog，每 leaseTime/3 通过 Lua 续期。",
                List.of("WatchDog", "Lua 原子续期", "客户端宕机自动释放"))
        )));

    CandidateAssessmentFeedbackDTO dto = service.buildIfRejected(app, job);

    assertThat(dto).isNotNull();
    assertThat(dto.rejectStageLabel()).isEqualTo("笔试 / 面试评估未通过");
    assertThat(dto.summary()).contains("分布式锁原子释放及 MySQL 隔离级别理解存在偏差");
    assertThat(dto.scoreSummary()).contains("综合得分 52 分").contains("在线笔试 33 分").contains("岗位通过线 70 分");
    assertThat(dto.reasons()).contains("笔试基础题失分较多", "高并发缓存与锁机制原理阐述不完整");

    // 笔试仅返回答错的第 0 题与第 2 题，不返回已答对的第 1 题
    assertThat(dto.failedWrittenQuestions()).hasSize(2);
    assertThat(dto.failedWrittenQuestions().get(0).index()).isZero();
    assertThat(dto.failedWrittenQuestions().get(0).correctAnswers()).containsExactly("B");
    assertThat(dto.failedWrittenQuestions().get(0).explanation()).contains("REPEATABLE READ");
    assertThat(dto.failedWrittenQuestions().get(1).index()).isEqualTo(2);
    assertThat(dto.failedWrittenQuestions().get(1).judgeReason()).contains("Shell 脚本无法在 Redis 服务端原子执行");

    // 面试仅返回未达 70 分线的第 1 题，不返回 82 分的第 0 题
    assertThat(dto.failedInterviewQuestions()).hasSize(1);
    assertThat(dto.failedInterviewQuestions().getFirst().questionIndex()).isEqualTo(1);
    assertThat(dto.failedInterviewQuestions().getFirst().score()).isEqualTo(45);
    assertThat(dto.failedInterviewQuestions().getFirst().passThreshold()).isEqualTo(70);
    assertThat(dto.failedInterviewQuestions().getFirst().feedback()).contains("每 10s 后台定时任务");
    assertThat(dto.failedInterviewQuestions().getFirst().referenceAnswer()).contains("WatchDog");
    assertThat(dto.failedInterviewQuestions().getFirst().keyPoints()).contains("WatchDog", "Lua 原子续期");
  }

  @Test
  @DisplayName("HR 复核未通过时绝不向候选人泄露 hrComment 内部审核评语，同时正常提供薄弱题目解析")
  void neverLeaksInternalHrCommentWhenHrRejected() {
    JobPositionEntity job = new JobPositionEntity();
    job.setAiPassThreshold(70);

    ApplicationEntity app = new ApplicationEntity();
    app.setId(601L);
    app.setStatus(ApplicationStatus.HR_REJECTED);
    app.setAiScore(75);
    app.setHrComment("内部评语：薪资预期超出部门 HC 预算上限，不外发");

    when(writtenTestRepository.findByApplicationId(601L)).thenReturn(Optional.empty());
    when(interviewResultReader.readEvaluatedDetail(app)).thenReturn(new EvaluatedInterview(
        75,
        "基础知识掌握尚可，但系统架构容灾经验偏弱",
        List.of("加强多活容灾与限流降级实战"),
        List.of(
            new EvaluatedAnswer(0, "架构设计", "如何设计异地多活数据同步？", "用主从复制", 72,
                "未考虑双向同步冲突解决与流量路由归属", "单元化路由 + 冲突幂等合并...", List.of("单元化", "冲突消解")),
            new EvaluatedAnswer(1, "Java", "ConcurrentHashMap 1.8 锁粒度优化", "CAS + synchronized", 88,
                "准确", "参考答案", List.of("CAS"))
        )));

    CandidateAssessmentFeedbackDTO dto = service.buildIfRejected(app, job);

    assertThat(dto).isNotNull();
    assertThat(dto.rejectStageLabel()).isEqualTo("综合复核未通过");
    assertThat(dto.summary()).doesNotContain("内部评语").doesNotContain("薪资预期").doesNotContain("HC 预算");
    assertThat(String.join(" ", dto.reasons())).doesNotContain("内部评语").doesNotContain("薪资预期");
    // 得分最低且 < 80 分的薄弱面试题正常提供复盘解析
    assertThat(dto.failedInterviewQuestions()).hasSize(1);
    assertThat(dto.failedInterviewQuestions().getFirst().questionIndex()).isZero();
  }
}
