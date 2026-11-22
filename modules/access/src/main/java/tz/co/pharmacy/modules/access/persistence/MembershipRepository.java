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

  /** Another member of the pharmacy already has this staff number (any case). */
  boolean existsByTenantUidAndStaffNumberIgnoreCaseAndUidNot(
      UUID tenantUid, String staffNumber, UUID uid);

  List<Membership> findAllByUserUid(UUID userUid);

  /**
   * When the member last worked in this pharmacy. Written directly, not through the entity, so the
   * bookkeeping leaves no "membership updated" entry in the pharmacy's audit every few minutes.
   */
  @org.springframework.data.jpa.repository.Modifying
  @org.springframework.data.jpa.repository.Query(
      "update Membership m set m.lastActiveAt = :at"
          + " where m.userUid = :user and m.tenantUid = :tenant")
  int workedAt(
      @org.springframework.data.repository.query.Param("user") UUID userUid,
      @org.springframework.data.repository.query.Param("tenant") UUID tenantUid,
      @org.springframework.data.repository.query.Param("at") java.time.Instant at);
}
