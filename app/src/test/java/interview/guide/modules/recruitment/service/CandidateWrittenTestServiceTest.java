package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.job.model.JobPositionEntity;
import interview.guide.modules.job.service.JobPositionService;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.CandidateWrittenTestDTO;
import interview.guide.modules.recruitment.model.WrittenAnswersRequest;
import interview.guide.modules.recruitment.model.WrittenQuestion;
import interview.guide.modules.recruitment.model.WrittenQuestionResult;
import interview.guide.modules.recruitment.model.WrittenQuestionType;
import interview.guide.modules.recruitment.model.WrittenTestEntity;
import interview.guide.modules.recruitment.model.WrittenTestStatus;
import interview.guide.modules.recruitment.repository.ApplicationRepository;
import interview.guide.modules.recruitment.repository.WrittenTestRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("候选人在线笔试服务测试")
class CandidateWrittenTestServiceTest {

  @Mock
  private ApplicationRepository applicationRepository;

  @Mock
  private WrittenTestRepository writtenTestRepository;

  @Mock
  private JobPositionService jobPositionService;

  @Mock
  private WrittenTestGenerator generator;

  @Mock
  private WrittenTestGrader grader;

  @Mock
  private TransactionalExecutor transactionalExecutor;

  @Mock
  private RedisService redisService;

  @Mock
  private RecruitmentInvitationService invitationService;

  private WrittenTestCodec codec;
  private CandidateWrittenTestService service;

  @BeforeEach
  void setUp() {
    codec = new WrittenTestCodec(new ObjectMapper());
    service = new CandidateWrittenTestService(
        applicationRepository, writtenTestRepository, jobPositionService,
        generator, grader, codec, transactionalExecutor, redisService, invitationService);
  }

  @Test
  @DisplayName("作答规范化过滤越界题号与非法选项，单选题只保留首项")
  void sanitizesCandidateAnswers() {
    List<WrittenQuestion> questions = List.of(
        new WrittenQuestion(0, WrittenQuestionType.SINGLE_CHOICE, "Q0",
            List.of("A", "B", "C"), List.of("A"), "", ""),
        new WrittenQuestion(1, WrittenQuestionType.MULTIPLE_CHOICE, "Q1",
            List.of("A", "B", "C", "D"), List.of("A", "C"), "", ""),
        new WrittenQuestion(2, WrittenQuestionType.FILL_BLANK, "Q2：____",
            List.of(), List.of("MVCC"), "", "")
    );

    Map<Integer, List<String>> cleaned = CandidateWrittenTestService.sanitizeAnswers(questions, Map.of(
        0, List.of("c", "a", "Z"),
        1, List.of("d", "b", "E"),
        2, List.of("  MVCC  "),
        99, List.of("A")
    ));

    assertThat(cleaned.get(0)).containsExactly("A");
    assertThat(cleaned.get(1)).containsExactly("B", "D");
    assertThat(cleaned.get(2)).containsExactly("MVCC");
    assertThat(cleaned).doesNotContainKey(99);
  }

  @Test
  @DisplayName("开始笔试校验岗位开关、延长临近过期的邀请有效期，交卷后自动判分并写回投递笔试分")
  void startsSubmitsAndGradesWrittenTest() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(200L);
    app.setCandidateId(10L);
    app.setJobId(30L);
    app.setCandidateName("张三");
    app.setStatus(ApplicationStatus.INTERVIEW_INVITED);
    LocalDateTime nearExpiry = LocalDateTime.now().plusMinutes(20);
    app.setInviteExpiresAt(nearExpiry);

    JobPositionEntity job = new JobPositionEntity();
    job.setId(30L);
    job.setWrittenTestEnabled(false);
    job.setWrittenTimeLimitMinutes(15);
    job.setProctorWatermark(true);

    when(applicationRepository.findByIdAndCandidateId(200L, 10L)).thenReturn(Optional.of(app));
    when(redisService.executeWithLock(any(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(inv -> ((RedisService.LockedOperation<?>) inv.getArgument(4)).execute());
    when(jobPositionService.getEntity(30L)).thenReturn(job);
    when(writtenTestRepository.findByApplicationId(200L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.start(10L, 200L))
        .isInstanceOf(BusinessException.class)
        .extracting(ex -> ((BusinessException) ex).getCode())
        .isEqualTo(ErrorCode.WRITTEN_TEST_NOT_AVAILABLE.getCode());

    // 开启笔试后正常出题并延长邀请有效期
    job.setWrittenTestEnabled(true);
    List<WrittenQuestion> generated = List.of(
        new WrittenQuestion(0, WrittenQuestionType.SINGLE_CHOICE, "1+1=?",
            List.of("1", "2", "3"), List.of("B"), "", "基础")
    );
    when(generator.generate(any(), any(), org.mockito.ArgumentMatchers.eq("senior"))).thenReturn(generated);
    when(applicationRepository.findByIdForUpdate(200L)).thenReturn(Optional.of(app));
    doAnswer(inv -> {
      ((Runnable) inv.getArgument(0)).run();
      return null;
    }).when(transactionalExecutor).run(any());

    AtomicReference<WrittenTestEntity> savedRef = new AtomicReference<>();
    when(writtenTestRepository.save(any())).thenAnswer(inv -> {
      WrittenTestEntity e = inv.getArgument(0);
      if (e.getId() == null) {
        e.setId(501L);
      }
      savedRef.set(e);
      return e;
    });
    when(writtenTestRepository.findByApplicationId(200L))
        .thenAnswer(inv -> Optional.ofNullable(savedRef.get()));

    CandidateWrittenTestDTO started = service.start(10L, 200L, "senior");
    assertThat(started.status()).isEqualTo(WrittenTestStatus.IN_PROGRESS);
    assertThat(started.candidateLevel()).isEqualTo("senior");
    assertThat(app.getCandidateLevel()).isEqualTo("senior");
    assertThat(started.questions()).hasSize(1);
    assertThat(started.watermarkText()).isEqualTo("张三 · #200");
    assertThat(app.getInviteExpiresAt()).isAfter(nearExpiry.plusHours(1));

    // 交卷并判分
    when(applicationRepository.findById(200L)).thenReturn(Optional.of(app));
    when(writtenTestRepository.findById(501L)).thenAnswer(inv -> Optional.ofNullable(savedRef.get()));
    when(writtenTestRepository.saveAnswersIfInProgress(anyLong(), any(), any())).thenAnswer(inv -> {
      savedRef.get().setAnswersJson(inv.getArgument(1));
      return 1;
    });
    when(writtenTestRepository.markSubmitted(anyLong(), any())).thenAnswer(inv -> {
      savedRef.get().setStatus(WrittenTestStatus.SUBMITTED);
      savedRef.get().setSubmittedAt(inv.getArgument(1));
      return 1;
    });
    when(grader.grade(any(), any(), any())).thenReturn(new WrittenTestGrader.GradeResult(
        List.of(new WrittenQuestionResult(0, true, List.of("B"), WrittenQuestionResult.RULE, null)), 100, 1));

    CandidateWrittenTestDTO submitted = service.submit(10L, 200L,
        new WrittenAnswersRequest(Map.of(0, List.of("B"))));

    assertThat(submitted.status()).isEqualTo(WrittenTestStatus.GRADED);
    assertThat(submitted.questions()).isEmpty();
    assertThat(app.getWrittenScore()).isEqualTo(100);
    verify(applicationRepository).save(app);
  }

  @Test
  @DisplayName("开始笔试支持选填 AI 经验与个人特长，落库并透传给 AI 笔试出题器")
  void startsWrittenTestWithOptionalAiExperienceAndSpecialties() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(210L);
    app.setCandidateId(10L);
    app.setJobId(31L);
    app.setCandidateName("赵六");
    app.setStatus(ApplicationStatus.INTERVIEW_INVITED);
    app.setInviteExpiresAt(LocalDateTime.now().plusDays(2));

    JobPositionEntity job = new JobPositionEntity();
    job.setId(31L);
    job.setWrittenTestEnabled(true);
    job.setWrittenTimeLimitMinutes(15);

    List<WrittenQuestion> generated = List.of(
        new WrittenQuestion(0, WrittenQuestionType.SINGLE_CHOICE, "RAG 检索增强的核心流程？",
            List.of("仅微调", "检索相关片段注入上下文生成", "随机采样"), List.of("B"), "", "AI 工程")
    );
    when(applicationRepository.findByIdAndCandidateId(210L, 10L)).thenReturn(Optional.of(app));
    when(applicationRepository.findByIdForUpdate(210L)).thenReturn(Optional.of(app));
    when(jobPositionService.getEntity(31L)).thenReturn(job);
    when(redisService.executeWithLock(any(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(inv -> ((RedisService.LockedOperation<?>) inv.getArgument(4)).execute());
    doAnswer(inv -> {
      ((Runnable) inv.getArgument(0)).run();
      return null;
    }).when(transactionalExecutor).run(any());
    when(generator.generate(any(), any(),
        org.mockito.ArgumentMatchers.eq("mid"),
        org.mockito.ArgumentMatchers.eq("TOOL_USER"),
        org.mockito.ArgumentMatchers.eq("熟悉 JVM 调优与 Cursor 辅助研发")))
        .thenReturn(generated);

    AtomicReference<WrittenTestEntity> savedRef = new AtomicReference<>();
    when(writtenTestRepository.save(any())).thenAnswer(inv -> {
      WrittenTestEntity e = inv.getArgument(0);
      e.setId(510L);
      savedRef.set(e);
      return e;
    });
    when(writtenTestRepository.findByApplicationId(210L))
        .thenAnswer(inv -> Optional.ofNullable(savedRef.get()));

    CandidateWrittenTestDTO dto = service.start(
        10L, 210L, "mid", "TOOL_USER", "  熟悉 JVM 调优与 Cursor 辅助研发  ");

    assertThat(dto.aiExperience()).isEqualTo("TOOL_USER");
    assertThat(dto.specialties()).isEqualTo("熟悉 JVM 调优与 Cursor 辅助研发");
    assertThat(app.getAiExperience()).isEqualTo("TOOL_USER");
    assertThat(app.getSpecialties()).isEqualTo("熟悉 JVM 调优与 Cursor 辅助研发");
  }

  @Test
  @DisplayName("开始笔试无需重复选择方向，自动读取投递时的偏向前端/后端偏好传给 AI 出题器")
  void startsWrittenTestUsingApplicationTrackAutomatically() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(220L);
    app.setCandidateId(10L);
    app.setJobId(32L);
    app.setCandidateName("陈前端");
    app.setCandidateLevel("mid");
    app.setCandidateTrack("FRONTEND");
    app.setStatus(ApplicationStatus.INTERVIEW_INVITED);
    app.setInviteExpiresAt(LocalDateTime.now().plusDays(2));

    JobPositionEntity job = new JobPositionEntity();
    job.setId(32L);
    job.setWrittenTestEnabled(true);
    job.setWrittenTimeLimitMinutes(15);

    List<WrittenQuestion> generated = List.of(
        new WrittenQuestion(0, WrittenQuestionType.SINGLE_CHOICE, "浏览器事件循环中微任务何时执行？",
            List.of("宏任务前", "当前宏任务同步栈清空后、下次渲染前", "仅定时器后"), List.of("B"), "", "JavaScript")
    );
    when(applicationRepository.findByIdAndCandidateId(220L, 10L)).thenReturn(Optional.of(app));
    when(applicationRepository.findByIdForUpdate(220L)).thenReturn(Optional.of(app));
    when(jobPositionService.getEntity(32L)).thenReturn(job);
    when(redisService.executeWithLock(any(), anyLong(), anyLong(), any(), any()))
        .thenAnswer(inv -> ((RedisService.LockedOperation<?>) inv.getArgument(4)).execute());
    doAnswer(inv -> {
      ((Runnable) inv.getArgument(0)).run();
      return null;
    }).when(transactionalExecutor).run(any());
    when(generator.generate(any(), any(),
        org.mockito.ArgumentMatchers.eq("mid"),
        org.mockito.ArgumentMatchers.eq("FRONTEND"),
        org.mockito.ArgumentMatchers.isNull(),
        org.mockito.ArgumentMatchers.isNull()))
        .thenReturn(generated);

    AtomicReference<WrittenTestEntity> savedRef = new AtomicReference<>();
    when(writtenTestRepository.save(any())).thenAnswer(inv -> {
      WrittenTestEntity e = inv.getArgument(0);
      e.setId(520L);
      savedRef.set(e);
      return e;
    });
    when(writtenTestRepository.findByApplicationId(220L))
        .thenAnswer(inv -> Optional.ofNullable(savedRef.get()));

    CandidateWrittenTestDTO dto = service.start(10L, 220L);

    assertThat(dto.candidateTrack()).isEqualTo("FRONTEND");
    assertThat(dto.questions()).hasSize(1);
    verify(generator).generate(any(), any(),
        org.mockito.ArgumentMatchers.eq("mid"),
        org.mockito.ArgumentMatchers.eq("FRONTEND"),
        org.mockito.ArgumentMatchers.isNull(),
        org.mockito.ArgumentMatchers.isNull());
  }

  @Test
  @DisplayName("笔试即使超过参考时长也不自动交卷，查询状态与自动保存继续保留草稿，仅调用 submit 时交卷判分")
  void doesNotAutoSubmitWhenOverdueUntilCandidateExplicitlySubmits() {
    ApplicationEntity app = new ApplicationEntity();
    app.setId(230L);
    app.setCandidateId(10L);
    app.setJobId(33L);
    app.setCandidateName("王五");
    app.setStatus(ApplicationStatus.INTERVIEW_INVITED);
    app.setInviteExpiresAt(LocalDateTime.now().plusDays(1));

    JobPositionEntity job = new JobPositionEntity();
    job.setId(33L);
    job.setWrittenTestEnabled(true);
    job.setWrittenTimeLimitMinutes(15);

    List<WrittenQuestion> questions = List.of(
        new WrittenQuestion(0, WrittenQuestionType.SINGLE_CHOICE, "HTTP 200 表示？",
            List.of("重定向", "请求成功", "服务器错误"), List.of("B"), "", "网络")
    );

    WrittenTestEntity overdueTest = new WrittenTestEntity();
    overdueTest.setId(530L);
    overdueTest.setApplicationId(230L);
    overdueTest.setStatus(WrittenTestStatus.IN_PROGRESS);
    overdueTest.setQuestionsJson(codec.write(questions));
    overdueTest.setAnswersJson(codec.write(Map.of()));
    overdueTest.setTotalCount(1);
    overdueTest.setStartedAt(LocalDateTime.now().minusMinutes(25));
    overdueTest.setDeadlineAt(LocalDateTime.now().minusMinutes(10));

    when(applicationRepository.findByIdAndCandidateId(230L, 10L)).thenReturn(Optional.of(app));
    when(jobPositionService.getEntity(33L)).thenReturn(job);
    when(writtenTestRepository.findByApplicationId(230L)).thenReturn(Optional.of(overdueTest));
    when(writtenTestRepository.saveAnswersIfInProgress(anyLong(), any(), any())).thenAnswer(inv -> {
      overdueTest.setAnswersJson(inv.getArgument(1));
      return 1;
    });

    // 1. 超过参考时长后查询笔试状态：仍保持 IN_PROGRESS，不自动交卷
    CandidateWrittenTestDTO stateAfterDeadline = service.getState(10L, 230L);
    assertThat(stateAfterDeadline.status()).isEqualTo(WrittenTestStatus.IN_PROGRESS);
    assertThat(stateAfterDeadline.remainingSeconds()).isZero();
    assertThat(stateAfterDeadline.questions()).hasSize(1);
    org.mockito.Mockito.verify(writtenTestRepository, org.mockito.Mockito.never()).markSubmitted(anyLong(), any());

    // 2. 超过参考时长后继续自动保存草稿：正常保存，不抛异常也不自动交卷
    service.saveAnswers(10L, 230L, new WrittenAnswersRequest(Map.of(0, List.of("A"))));
    assertThat(overdueTest.getStatus()).isEqualTo(WrittenTestStatus.IN_PROGRESS);
    assertThat(codec.readAnswers(overdueTest.getAnswersJson()).get(0)).containsExactly("A");
    org.mockito.Mockito.verify(writtenTestRepository, org.mockito.Mockito.never()).markSubmitted(anyLong(), any());

    // 3. 候选人主动点击提交按钮确认交卷：正常更新最终答案并完成判分
    when(applicationRepository.findById(230L)).thenReturn(Optional.of(app));
    when(writtenTestRepository.findById(530L)).thenReturn(Optional.of(overdueTest));
    when(writtenTestRepository.markSubmitted(anyLong(), any())).thenAnswer(inv -> {
      overdueTest.setStatus(WrittenTestStatus.SUBMITTED);
      overdueTest.setSubmittedAt(inv.getArgument(1));
      return 1;
    });
    doAnswer(inv -> {
      ((Runnable) inv.getArgument(0)).run();
      return null;
    }).when(transactionalExecutor).run(any());
    when(grader.grade(any(), any(), any())).thenReturn(new WrittenTestGrader.GradeResult(
        List.of(new WrittenQuestionResult(0, true, List.of("B"), WrittenQuestionResult.RULE, null)), 100, 1));

    CandidateWrittenTestDTO submitted = service.submit(10L, 230L,
        new WrittenAnswersRequest(Map.of(0, List.of("B"))));
    assertThat(submitted.status()).isEqualTo(WrittenTestStatus.GRADED);
    assertThat(codec.readAnswers(overdueTest.getAnswersJson()).get(0)).containsExactly("B");
    assertThat(app.getWrittenScore()).isEqualTo(100);
  }
}

