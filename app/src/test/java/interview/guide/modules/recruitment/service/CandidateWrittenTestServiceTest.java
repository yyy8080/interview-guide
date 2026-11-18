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

  private WrittenTestCodec codec;
  private CandidateWrittenTestService service;

  @BeforeEach
  void setUp() {
    codec = new WrittenTestCodec(new ObjectMapper());
    service = new CandidateWrittenTestService(
        applicationRepository, writtenTestRepository, jobPositionService,
        generator, grader, codec, transactionalExecutor, redisService);
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
}
