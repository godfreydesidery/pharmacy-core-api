package tz.co.pharmacy.modules.access.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.access.domain.Membership;

/**
 * Memberships the transaction may see: the current tenant's, and the current user's own in every
 * tenant. Tenant-scoped reads therefore always name the tenant too.
 */
public interface MembershipRepository
    extends JpaRepository<Membership, Long>, JpaSpecificationExecutor<Membership> {

  Optional<Membership> findByUidAndTenantUid(UUID uid, UUID tenantUid);

  Optional<Membership> findByUserUidAndTenantUid(UUID userUid, UUID tenantUid);

  boolean existsByUserUidAndTenantUid(UUID userUid, UUID tenantUid);

  List<Membership> findAllByUserUid(UUID userUid);
}
