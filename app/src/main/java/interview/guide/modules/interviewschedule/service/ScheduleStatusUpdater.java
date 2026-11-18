package interview.guide.modules.interviewschedule.service;

import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScheduleStatusUpdater {

    private final InterviewScheduleRepository repository;

    @Scheduled(cron = "0 0 * * * ?")
    @Transactional
    public void updateExpiredInterviews() {
        LocalDateTime now = LocalDateTime.now();
        int updated = repository.updateManualStatusBefore(
            InterviewStatus.CANCELLED, InterviewStatus.PENDING, now);

        if (updated > 0) {
            log.info("已将 {} 条过期面试标记为已取消", updated);
        }

        // 招聘流程创建的线下面试：时间已过视为已完成，不应被标记为取消
        int completed = repository.updateRecruitmentStatusBefore(
            InterviewStatus.COMPLETED, InterviewStatus.PENDING, now.minusHours(2));
        if (completed > 0) {
            log.info("已将 {} 条招聘线下面试标记为已完成", completed);
        }
    }
}
