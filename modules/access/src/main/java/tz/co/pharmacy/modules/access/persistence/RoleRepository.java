package tz.co.pharmacy.modules.access.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.access.domain.Role;

/** Roles of the current tenant (row-level security). */
public interface RoleRepository extends JpaRepository<Role, Long> {

  Optional<Role> findByUid(UUID uid);

  Optional<Role> findByCode(String code);

  boolean existsByCode(String code);

  List<Role> findAllByOrderByNameAsc();
}
