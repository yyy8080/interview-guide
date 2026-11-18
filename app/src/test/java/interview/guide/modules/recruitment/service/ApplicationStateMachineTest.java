package interview.guide.modules.recruitment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.ApplicationStatusLogEntity;
import interview.guide.modules.recruitment.model.CandidateStage;
import interview.guide.modules.recruitment.model.OperatorType;
import interview.guide.modules.recruitment.repository.ApplicationStatusLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

@ExtendWith(MockitoExtension.class)
@DisplayName("投递状态机与状态定义测试")
class ApplicationStateMachineTest {

  @Mock
  private ApplicationStatusLogRepository statusLogRepository;

  @Mock
  private ApplicationEventPublisher eventPublisher;

  private ApplicationStateMachine stateMachine;

  @BeforeEach
  void setUp() {
    stateMachine = new ApplicationStateMachine(statusLogRepository, eventPublisher);
  }

  @Nested
  @DisplayName("状态迁移规则与终态约束")
  class StatusTransitionRules {

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    @DisplayName("AI 初筛淘汰、AI 复审淘汰、HR 淘汰均为严格终态，不可再迁移到任意状态")
    void rejectedStatesAreTerminal(ApplicationStatus target) {
      assertThat(ApplicationStatus.SCREEN_REJECTED.canTransitTo(target)).isFalse();
      assertThat(ApplicationStatus.AI_REJECTED.canTransitTo(target)).isFalse();
      assertThat(ApplicationStatus.HR_REJECTED.canTransitTo(target)).isFalse();
    }

    @Test
    @DisplayName("核心正向招聘链路与线下面试改期迁移合法")
    void allowsHappyPathAndRescheduleTransitions() {
      assertThat(ApplicationStatus.SUBMITTED.canTransitTo(ApplicationStatus.SCREENING)).isTrue();
      assertThat(ApplicationStatus.SUBMITTED.canTransitTo(ApplicationStatus.INTERVIEW_INVITED)).isTrue();
      assertThat(ApplicationStatus.SCREENING.canTransitTo(ApplicationStatus.INTERVIEW_INVITED)).isTrue();
      assertThat(ApplicationStatus.SCREENING.canTransitTo(ApplicationStatus.SCREEN_REJECTED)).isTrue();
      assertThat(ApplicationStatus.INTERVIEW_INVITED.canTransitTo(ApplicationStatus.INTERVIEWING)).isTrue();
      assertThat(ApplicationStatus.INTERVIEWING.canTransitTo(ApplicationStatus.AI_REVIEWING)).isTrue();
      assertThat(ApplicationStatus.AI_REVIEWING.canTransitTo(ApplicationStatus.AI_PASSED)).isTrue();
      assertThat(ApplicationStatus.AI_REVIEWING.canTransitTo(ApplicationStatus.AI_REJECTED)).isTrue();
      assertThat(ApplicationStatus.AI_PASSED.canTransitTo(ApplicationStatus.HR_APPROVED)).isTrue();
      assertThat(ApplicationStatus.HR_APPROVED.canTransitTo(ApplicationStatus.OFFLINE_SCHEDULED)).isTrue();
      assertThat(ApplicationStatus.OFFLINE_SCHEDULED.canTransitTo(ApplicationStatus.OFFLINE_SCHEDULED)).isTrue();
      assertThat(ApplicationStatus.OFFLINE_SCHEDULED.canTransitTo(ApplicationStatus.OFFLINE_CONFIRMED)).isTrue();
      assertThat(ApplicationStatus.OFFLINE_CONFIRMED.canTransitTo(ApplicationStatus.OFFLINE_SCHEDULED)).isTrue();
    }

    @Test
    @DisplayName("候选人可见阶段映射屏蔽内部 AI/HR 细分细节")
    void mapsInternalStatusToCandidateStage() {
      assertThat(ApplicationStatus.SUBMITTED.toCandidateStage()).isEqualTo(CandidateStage.SCREENING);
      assertThat(ApplicationStatus.SCREENING.toCandidateStage()).isEqualTo(CandidateStage.SCREENING);
      assertThat(ApplicationStatus.AI_REVIEWING.toCandidateStage()).isEqualTo(CandidateStage.UNDER_REVIEW);
      assertThat(ApplicationStatus.AI_PASSED.toCandidateStage()).isEqualTo(CandidateStage.UNDER_REVIEW);
      assertThat(ApplicationStatus.SCREEN_REJECTED.toCandidateStage()).isEqualTo(CandidateStage.REJECTED);
      assertThat(ApplicationStatus.AI_REJECTED.toCandidateStage()).isEqualTo(CandidateStage.REJECTED);
      assertThat(ApplicationStatus.HR_REJECTED.toCandidateStage()).isEqualTo(CandidateStage.REJECTED);
      assertThat(ApplicationStatus.OFFLINE_SCHEDULED.toCandidateStage()).isEqualTo(CandidateStage.OFFLINE_INVITED);
    }
  }

  @Nested
  @DisplayName("状态机执行、审计日志与事件发布")
  class ExecutionAndAudit {

    @Test
    @DisplayName("初始化投递单写入初始审计日志并发布 SUBMITTED 事件")
    void initializesSubmittedApplication() {
      ApplicationEntity app = new ApplicationEntity();
      app.setId(10L);
      app.setCandidateId(77L);
      app.setStatus(ApplicationStatus.SUBMITTED);

      stateMachine.initialize(app, "候选人投递简历");

      ArgumentCaptor<ApplicationStatusLogEntity> logCaptor = ArgumentCaptor.forClass(ApplicationStatusLogEntity.class);
      verify(statusLogRepository).save(logCaptor.capture());
      assertThat(logCaptor.getValue().getFromStatus()).isNull();
      assertThat(logCaptor.getValue().getToStatus()).isEqualTo(ApplicationStatus.SUBMITTED);
      assertThat(logCaptor.getValue().getOperatorType()).isEqualTo(OperatorType.CANDIDATE);
      assertThat(logCaptor.getValue().getOperatorId()).isEqualTo(77L);

      verify(eventPublisher).publishEvent(
          new ApplicationStatusChangedEvent(10L, null, ApplicationStatus.SUBMITTED));
    }

    @Test
    @DisplayName("合法状态迁移更新阶段时间戳、截断超长备注并发布领域事件")
    void transitsAndTouchesTimestamps() {
      ApplicationEntity app = new ApplicationEntity();
      app.setId(11L);
      app.setStatus(ApplicationStatus.INTERVIEW_INVITED);
      String longRemark = "备".repeat(1200);

      stateMachine.transit(app, ApplicationStatus.INTERVIEWING, OperatorType.CANDIDATE, 88L, longRemark);

      assertThat(app.getStatus()).isEqualTo(ApplicationStatus.INTERVIEWING);
      assertThat(app.getInterviewStartedAt()).isNotNull();

      ArgumentCaptor<ApplicationStatusLogEntity> logCaptor = ArgumentCaptor.forClass(ApplicationStatusLogEntity.class);
      verify(statusLogRepository).save(logCaptor.capture());
      assertThat(logCaptor.getValue().getRemark()).hasSize(1000);

      verify(eventPublisher).publishEvent(
          new ApplicationStatusChangedEvent(11L, ApplicationStatus.INTERVIEW_INVITED, ApplicationStatus.INTERVIEWING));
    }

    @Test
    @DisplayName("非法状态迁移抛出 APPLICATION_STATUS_INVALID 业务异常")
    void rejectsIllegalTransition() {
      ApplicationEntity app = new ApplicationEntity();
      app.setId(12L);
      app.setStatus(ApplicationStatus.AI_REJECTED);

      assertThatThrownBy(() ->
          stateMachine.transit(app, ApplicationStatus.HR_APPROVED, OperatorType.HR, 1L, "尝试强行通过"))
          .isInstanceOf(BusinessException.class)
          .extracting(ex -> ((BusinessException) ex).getCode())
          .isEqualTo(ErrorCode.APPLICATION_STATUS_INVALID.getCode());
    }
  }
}
