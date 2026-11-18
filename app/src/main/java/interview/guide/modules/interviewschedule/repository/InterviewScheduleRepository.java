package interview.guide.modules.interviewschedule.repository;

import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface InterviewScheduleRepository extends JpaRepository<InterviewScheduleEntity, Long> {
    List<InterviewScheduleEntity> findByStatusAndInterviewTimeBefore(InterviewStatus status, LocalDateTime time);

    List<InterviewScheduleEntity> findByStatus(InterviewStatus status);

    List<InterviewScheduleEntity> findByInterviewTimeBetween(LocalDateTime start, LocalDateTime end);

    @Modifying
    @Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus WHERE e.status = :oldStatus AND e.interviewTime < :cutoff")
    int updateStatusByStatusAndInterviewTimeBefore(
        @Param("newStatus") InterviewStatus newStatus,
        @Param("oldStatus") InterviewStatus oldStatus,
        @Param("cutoff") LocalDateTime cutoff);

    /** 仅更新手工录入（非招聘流程）的日程 */
    @Modifying
    @Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus WHERE e.status = :oldStatus "
        + "AND e.interviewTime < :cutoff AND e.applicationId IS NULL")
    int updateManualStatusBefore(
        @Param("newStatus") InterviewStatus newStatus,
        @Param("oldStatus") InterviewStatus oldStatus,
        @Param("cutoff") LocalDateTime cutoff);

    /** 招聘线下面试：面试时间（含时长）已过视为已完成 */
    @Modifying
    @Query("UPDATE InterviewScheduleEntity e SET e.status = :newStatus WHERE e.status = :oldStatus "
        + "AND e.interviewTime < :cutoff AND e.applicationId IS NOT NULL")
    int updateRecruitmentStatusBefore(
        @Param("newStatus") InterviewStatus newStatus,
        @Param("oldStatus") InterviewStatus oldStatus,
        @Param("cutoff") LocalDateTime cutoff);

    java.util.Optional<InterviewScheduleEntity> findByApplicationId(Long applicationId);
}
