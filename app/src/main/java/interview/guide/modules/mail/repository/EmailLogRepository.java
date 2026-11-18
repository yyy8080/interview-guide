package interview.guide.modules.mail.repository;

import interview.guide.modules.mail.model.EmailLogEntity;
import interview.guide.modules.mail.model.EmailLogStatus;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface EmailLogRepository extends JpaRepository<EmailLogEntity, Long> {

  Page<EmailLogEntity> findAllByOrderByIdDesc(Pageable pageable);

  Page<EmailLogEntity> findByStatusOrderByIdDesc(EmailLogStatus status, Pageable pageable);

  List<EmailLogEntity> findByApplicationIdOrderByIdDesc(Long applicationId);

  /** 到期待发送的邮件 */
  @Query("""
      SELECT e.id FROM EmailLogEntity e
      WHERE e.status = interview.guide.modules.mail.model.EmailLogStatus.PENDING
        AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now)
      ORDER BY e.id
      """)
  List<Long> findDueIds(@Param("now") LocalDateTime now, Pageable pageable);

  /** 条件领取：PENDING → SENDING，返回 1 表示领取成功（多实例防重复发送） */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE EmailLogEntity e
      SET e.status = interview.guide.modules.mail.model.EmailLogStatus.SENDING, e.nextAttemptAt = :now
      WHERE e.id = :id AND e.status = interview.guide.modules.mail.model.EmailLogStatus.PENDING
      """)
  int claim(@Param("id") Long id, @Param("now") LocalDateTime now);

  /** 发送中卡死（进程崩溃）的邮件恢复为待发送 */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("""
      UPDATE EmailLogEntity e
      SET e.status = interview.guide.modules.mail.model.EmailLogStatus.PENDING
      WHERE e.status = interview.guide.modules.mail.model.EmailLogStatus.SENDING
        AND e.nextAttemptAt < :before
      """)
  int releaseStuck(@Param("before") LocalDateTime before);
}
