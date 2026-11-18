package interview.guide.modules.recruitment.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import interview.guide.modules.recruitment.model.ApplicationStatusLogEntity;
import interview.guide.modules.recruitment.model.OperatorType;
import interview.guide.modules.recruitment.repository.ApplicationStatusLogRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 投递状态机：唯一允许修改 {@link ApplicationEntity#getStatus()} 的入口。
 * <p>
 * 校验迁移合法性、维护阶段时间戳、写审计日志，并发布 {@link ApplicationStatusChangedEvent}
 * （事务提交后由监听器发送通知邮件等）。调用方需处于事务中。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApplicationStateMachine {

  private static final int REMARK_MAX_LENGTH = 1000;

  private final ApplicationStatusLogRepository statusLogRepository;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * 投递单保存（status = SUBMITTED）后记录初始状态日志
   */
  public void initialize(ApplicationEntity application, String remark) {
    if (application.getId() == null || application.getStatus() != ApplicationStatus.SUBMITTED) {
      throw new IllegalStateException("投递单需先以 SUBMITTED 状态保存");
    }
    writeLog(application.getId(), null, ApplicationStatus.SUBMITTED, OperatorType.CANDIDATE,
        application.getCandidateId(), remark);
    eventPublisher.publishEvent(
        new ApplicationStatusChangedEvent(application.getId(), null, ApplicationStatus.SUBMITTED));
  }

  /**
   * 状态迁移；非法迁移抛出 APPLICATION_STATUS_INVALID
   */
  public void transit(ApplicationEntity application, ApplicationStatus target,
                      OperatorType operatorType, Long operatorId, String remark) {
    ApplicationStatus current = application.getStatus();
    if (current == null || !current.canTransitTo(target)) {
      throw new BusinessException(ErrorCode.APPLICATION_STATUS_INVALID,
          "当前状态（" + current + "）不允许变更为 " + target);
    }
    application.setStatus(target);
    touchTimestamps(application, target);
    writeLog(application.getId(), current, target, operatorType, operatorId, remark);
    log.info("投递状态变更: applicationId={}, {} -> {}, operator={}:{}",
        application.getId(), current, target, operatorType, operatorId);
    eventPublisher.publishEvent(new ApplicationStatusChangedEvent(application.getId(), current, target));
  }

  /**
   * 记录不改变状态的操作日志（如候选人申请改期、HR 备注）
   */
  public void log(ApplicationEntity application, OperatorType operatorType, Long operatorId, String remark) {
    writeLog(application.getId(), application.getStatus(), application.getStatus(), operatorType, operatorId, remark);
  }

  /** 当前状态是否允许迁移到目标状态 */
  public boolean canTransit(ApplicationEntity application, ApplicationStatus target) {
    return application.getStatus() != null && application.getStatus().canTransitTo(target);
  }

  private void touchTimestamps(ApplicationEntity application, ApplicationStatus target) {
    LocalDateTime now = LocalDateTime.now();
    switch (target) {
      case SCREENING -> application.setDecidedAt(null);
      case INTERVIEWING -> application.setInterviewStartedAt(now);
      case AI_REVIEWING -> application.setInterviewCompletedAt(now);
      case AI_PASSED, AI_REJECTED, SCREEN_REJECTED -> application.setDecidedAt(now);
      default -> {
        // 其他状态的时间戳由业务方法维护
      }
    }
  }

  private void writeLog(Long applicationId, ApplicationStatus from, ApplicationStatus to,
                        OperatorType operatorType, Long operatorId, String remark) {
    ApplicationStatusLogEntity entry = new ApplicationStatusLogEntity();
    entry.setApplicationId(applicationId);
    entry.setFromStatus(from);
    entry.setToStatus(to);
    entry.setOperatorType(operatorType);
    entry.setOperatorId(operatorId);
    if (remark != null && remark.length() > REMARK_MAX_LENGTH) {
      remark = remark.substring(0, REMARK_MAX_LENGTH);
    }
    entry.setRemark(remark);
    statusLogRepository.save(entry);
  }
}
