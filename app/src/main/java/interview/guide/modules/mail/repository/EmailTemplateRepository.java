package interview.guide.modules.mail.repository;

import interview.guide.modules.mail.model.EmailTemplateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EmailTemplateRepository extends JpaRepository<EmailTemplateEntity, String> {
}
