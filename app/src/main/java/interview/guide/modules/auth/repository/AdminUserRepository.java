package interview.guide.modules.auth.repository;

import interview.guide.modules.auth.model.AdminRole;
import interview.guide.modules.auth.model.AdminUserEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AdminUserRepository extends JpaRepository<AdminUserEntity, Long> {

  Optional<AdminUserEntity> findByUsername(String username);

  boolean existsByUsername(String username);

  long countByRoleAndEnabledTrue(AdminRole role);

  List<AdminUserEntity> findAllByOrderByCreatedAtAsc();
}
