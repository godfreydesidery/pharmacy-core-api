package tz.co.pharmacy.modules.access.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import tz.co.pharmacy.modules.access.domain.Invitation;

/**
 * Invitations the transaction may see: the current tenant's, or the one whose token hash the
 * transaction names (row-level security). Tenant reads therefore name the tenant too.
 */
public interface InvitationRepository
    extends JpaRepository<Invitation, Long>, JpaSpecificationExecutor<Invitation> {

  Optional<Invitation> findByTokenHash(String tokenHash);

  Optional<Invitation> findByUidAndTenantUid(UUID uid, UUID tenantUid);

  List<Invitation> findTop100ByTenantUidOrderByIdDesc(UUID tenantUid);

  List<Invitation> findAllByTenantUidAndEmailAndAcceptedAtIsNullAndRevokedAtIsNull(
      UUID tenantUid, String email);
}
