package interview.guide.modules.recruitment.repository;

import interview.guide.modules.recruitment.model.ApplicationEntity;
import interview.guide.modules.recruitment.model.ApplicationStatus;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface ApplicationRepository extends JpaRepository<ApplicationEntity, Long> {

  boolean existsByJobIdAndCandidateId(Long jobId, Long candidateId);

  boolean existsByResumeId(Long resumeId);

  long countByJobId(Long jobId);

  List<ApplicationEntity> findByCandidateIdOrderBySubmittedAtDesc(Long candidateId);

  Optional<ApplicationEntity> findByIdAndCandidateId(Long id, Long candidateId);

  Optional<ApplicationEntity> findByInterviewSessionId(String interviewSessionId);

  Optional<ApplicationEntity> findByVoiceSessionId(Long voiceSessionId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT a FROM ApplicationEntity a WHERE a.id = :id")
  Optional<ApplicationEntity> findByIdForUpdate(@Param("id") Long id);

  /**
   * 后台投递列表：按岗位 / 状态 / 关键字（姓名、邮箱、手机号）筛选
   */
  @Query(value = """
      SELECT a FROM ApplicationEntity a, CandidateEntity c
      WHERE c.id = a.candidateId
        AND (:jobId IS NULL OR a.jobId = :jobId)
        AND (:allStatuses = TRUE OR a.status IN :statuses)
        AND (LOWER(a.candidateName) LIKE :pattern OR LOWER(c.email) LIKE :pattern
             OR COALESCE(a.candidatePhone, '') LIKE :pattern)
      """,
      countQuery = """
      SELECT COUNT(a) FROM ApplicationEntity a, CandidateEntity c
      WHERE c.id = a.candidateId
        AND (:jobId IS NULL OR a.jobId = :jobId)
        AND (:allStatuses = TRUE OR a.status IN :statuses)
        AND (LOWER(a.candidateName) LIKE :pattern OR LOWER(c.email) LIKE :pattern
             OR COALESCE(a.candidatePhone, '') LIKE :pattern)
      """)
  Page<ApplicationEntity> search(@Param("jobId") Long jobId,
                                 @Param("allStatuses") boolean allStatuses,
                                 @Param("statuses") Collection<ApplicationStatus> statuses,
                                 @Param("pattern") String pattern,
                                 Pageable pageable);

  /** 异步任务超时未推进的投递（用于恢复调度） */
  @Query("""
      SELECT a.id FROM ApplicationEntity a
      WHERE a.status IN :statuses
        AND (a.taskUpdatedAt IS NULL OR a.taskUpdatedAt < :before)
      ORDER BY a.id
      """)
  List<Long> findStaleTaskIds(@Param("statuses") Collection<ApplicationStatus> statuses,
                              @Param("before") LocalDateTime before,
                              Pageable pageable);

  /** 邀请已过期仍未开始面试 */
  @Query("""
      SELECT a.id FROM ApplicationEntity a
      WHERE a.status = interview.guide.modules.recruitment.model.ApplicationStatus.INTERVIEW_INVITED
        AND a.inviteExpiresAt IS NOT NULL AND a.inviteExpiresAt < :now
      ORDER BY a.id
      """)
  List<Long> findExpiredInvitationIds(@Param("now") LocalDateTime now, Pageable pageable);

  /** 作答超时仍在面试中 */
  @Query("""
      SELECT a.id FROM ApplicationEntity a
      WHERE a.status = interview.guide.modules.recruitment.model.ApplicationStatus.INTERVIEWING
        AND a.interviewDeadlineAt IS NOT NULL AND a.interviewDeadlineAt < :now
      ORDER BY a.id
      """)
  List<Long> findOverdueInterviewIds(@Param("now") LocalDateTime now, Pageable pageable);

  @Query("""
      SELECT a.status AS status, COUNT(a) AS total
      FROM ApplicationEntity a
      WHERE (:jobId IS NULL OR a.jobId = :jobId)
      GROUP BY a.status
      """)
  List<StatusCount> countByStatus(@Param("jobId") Long jobId);

  @Query("""
      SELECT a.jobId AS jobId, COUNT(a) AS total
      FROM ApplicationEntity a
      WHERE a.jobId IN :jobIds
      GROUP BY a.jobId
      """)
  List<JobCount> countByJobIds(@Param("jobIds") Collection<Long> jobIds);

  interface StatusCount {
    ApplicationStatus getStatus();

    long getTotal();
  }

  interface JobCount {
    Long getJobId();

    long getTotal();
  }

  /** 指定状态的投递 ID（调度扫描用） */
  @Query("SELECT a.id FROM ApplicationEntity a WHERE a.status = :status ORDER BY a.id")
  List<Long> findIdsByStatus(@Param("status") ApplicationStatus status, Pageable pageable);

  /**
   * 条件领取异步任务：状态匹配且当前无执行者（或执行者已超时）时写入新的执行代次
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE ApplicationEntity a
      SET a.taskAttemptId = :attemptId, a.taskUpdatedAt = :now, a.version = a.version + 1
      WHERE a.id = :id AND a.status = :status
        AND (a.taskAttemptId IS NULL OR a.taskUpdatedAt IS NULL OR a.taskUpdatedAt < :staleBefore)
      """)
  int claimTask(@Param("id") Long id, @Param("status") ApplicationStatus status,
                @Param("attemptId") String attemptId, @Param("now") LocalDateTime now,
                @Param("staleBefore") LocalDateTime staleBefore);

  /** 释放异步任务执行权（失败重试前），仅当执行代次匹配时生效 */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE ApplicationEntity a
      SET a.taskAttemptId = NULL, a.taskError = :error, a.taskUpdatedAt = :now, a.version = a.version + 1
      WHERE a.id = :id AND a.taskAttemptId = :attemptId
      """)
  int releaseTask(@Param("id") Long id, @Param("attemptId") String attemptId,
                  @Param("error") String error, @Param("now") LocalDateTime now);

  /** 恢复调度重新入队：恢复计数 +1 并推进时间，返回 1 表示本实例负责入队 */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE ApplicationEntity a
      SET a.taskRetryCount = a.taskRetryCount + 1, a.taskAttemptId = NULL, a.taskUpdatedAt = :now,
          a.version = a.version + 1
      WHERE a.id = :id AND a.status = :status
        AND (a.taskUpdatedAt IS NULL OR a.taskUpdatedAt < :staleBefore)
      """)
  int markRequeued(@Param("id") Long id, @Param("status") ApplicationStatus status,
                   @Param("now") LocalDateTime now, @Param("staleBefore") LocalDateTime staleBefore);
}
