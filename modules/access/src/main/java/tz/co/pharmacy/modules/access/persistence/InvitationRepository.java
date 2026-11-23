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

  /** The pharmacy's invitations still open at {@code now}: each holds a seat of the users cap. */
  @org.springframework.data.jpa.repository.Query(
      "select count(i) from Invitation i where i.tenantUid = :tenantUid and i.acceptedAt is null"
          + " and i.revokedAt is null and i.expiresAt > :now")
  long countOpen(
      @org.springframework.data.repository.query.Param("tenantUid") UUID tenantUid,
      @org.springframework.data.repository.query.Param("now") java.time.Instant now);
}
