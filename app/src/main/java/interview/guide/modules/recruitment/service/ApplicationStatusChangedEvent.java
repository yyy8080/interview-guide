package interview.guide.modules.recruitment.service;

import interview.guide.modules.recruitment.model.ApplicationStatus;

/**
 * 投递状态变更事件（事务提交后处理：发送邮件、投递异步任务等）
 *
 * @param applicationId 投递单 ID
 * @param from          变更前状态，新建时为 null
 * @param to            变更后状态
 */
public record ApplicationStatusChangedEvent(Long applicationId, ApplicationStatus from, ApplicationStatus to) {}
