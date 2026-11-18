package interview.guide.modules.mail.repository;

import interview.guide.modules.mail.model.MailSettingsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MailSettingsRepository extends JpaRepository<MailSettingsEntity, Long> {
}
