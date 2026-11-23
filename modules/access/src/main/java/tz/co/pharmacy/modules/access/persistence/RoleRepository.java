package tz.co.pharmacy.modules.access.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import tz.co.pharmacy.modules.access.domain.Role;

/**
 * Roles the transaction may see (row-level security): the current tenant's, and also the roles of
 * the signed-in person's own memberships in other pharmacies (to sign in there). A pharmacy's own
 * reads therefore always name the tenant too (adversarial review ISO-7).
 */
public interface RoleRepository extends JpaRepository<Role, Long> {

  Optional<Role> findByUidAndTenantUid(UUID uid, UUID tenantUid);

  Optional<Role> findByTenantUidAndCode(UUID tenantUid, String code);

  boolean existsByTenantUidAndCode(UUID tenantUid, String code);

  List<Role> findAllByTenantUidOrderByNameAsc(UUID tenantUid);
}
