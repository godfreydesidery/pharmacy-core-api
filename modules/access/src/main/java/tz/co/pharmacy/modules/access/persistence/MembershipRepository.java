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

  /** This pharmacy's memberships of these people, active or not. */
  List<Membership> findAllByTenantUidAndUserUidIn(
      UUID tenantUid, java.util.Collection<UUID> userUids);

  /** The pharmacy's active members (who else could approve: Approvers.selfApprovalAllowed). */
  List<Membership> findAllByTenantUidAndActiveTrue(UUID tenantUid);

  /** The pharmacy's active members: the seats its plan's users cap counts with its invitations. */
  long countByTenantUidAndActiveTrue(UUID tenantUid);

  /**
   * Holds the pharmacy's users-cap lock until the transaction ends, so two invitations, logins,
   * reactivations or acceptances at once cannot both take the last seat: the second waits, then
   * counts what the first committed (INT-6). An advisory lock rather than a row lock, because what
   * is counted (members and invitations) has no single row to lock.
   */
  @org.springframework.data.jpa.repository.Query(
      value =
          "select 1 from (select pg_advisory_xact_lock(hashtextextended('access.users-cap:'"
              + " || cast(:tenantUid as text), 0))) held",
      nativeQuery = true)
  Integer lockSeats(@org.springframework.data.repository.query.Param("tenantUid") UUID tenantUid);

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
