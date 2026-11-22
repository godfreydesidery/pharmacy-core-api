package tz.co.pharmacy.modules.access.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import tz.co.pharmacy.modules.access.domain.Session;

public interface SessionRepository extends JpaRepository<Session, Long> {

  Optional<Session> findByTokenHash(String tokenHash);

  Optional<Session> findByUid(UUID uid);

  boolean existsByUserUid(UUID userUid);

  boolean existsByUserUidAndUserAgent(UUID userUid, String userAgent);

  @Query(
      "select s from Session s where s.userUid = :userUid and s.revokedAt is null"
          + " and s.expiresAt > :at order by s.id desc")
  List<Session> findLive(@Param("userUid") UUID userUid, @Param("at") Instant at);

  /** Records use without a version bump or an audit entry; called at most every few minutes. */
  @Modifying
  @Query("update Session s set s.lastSeenAt = :at where s.id = :id")
  int touch(@Param("id") Long id, @Param("at") Instant at);

  /**
   * The person used the app: the inactivity clock restarts. The limit (G17) is the strictest of the
   * pharmacies the session has worked in ({@code fromPharmacy}): the first pharmacy sets it, a
   * stricter one lowers it, a laxer one leaves it, so two tabs in two pharmacies do not flip it.
   * Before any pharmacy, the platform's limit applies. Without a version bump or an audit entry;
   * the web app calls it at most every half minute.
   */
  @Modifying
  @Query(
      "update Session s set s.activeAt = :at, s.lastSeenAt = :at,"
          + " s.idleMinutes = case"
          + "   when :fromPharmacy = false and s.idlePinned = true then s.idleMinutes"
          + "   when :fromPharmacy = false then :idleMinutes"
          + "   when s.idlePinned = false or s.idleMinutes is null then :idleMinutes"
          + "   when :idleMinutes < s.idleMinutes then :idleMinutes"
          + "   else s.idleMinutes end,"
          + " s.idlePinned = case when :fromPharmacy = true then true else s.idlePinned end"
          + " where s.id = :id")
  int markActive(
      @Param("id") Long id,
      @Param("at") Instant at,
      @Param("idleMinutes") Integer idleMinutes,
      @Param("fromPharmacy") boolean fromPharmacy);

  /** Revokes the session unless already revoked; 1 when this call ended it. */
  @Modifying
  @Query("update Session s set s.revokedAt = :at where s.id = :id and s.revokedAt is null")
  int revokeIfLive(@Param("id") Long id, @Param("at") Instant at);

  @Modifying
  @Query(
      "update Session s set s.revokedAt = :at where s.userUid = :userUid and s.revokedAt is null"
          + " and (:keepHash is null or s.tokenHash <> :keepHash)")
  int revokeAll(
      @Param("userUid") UUID userUid, @Param("at") Instant at, @Param("keepHash") String keepHash);

  /** The pharmacies session {@code id} has worked in. */
  @Query(
      value =
          "select sp.tenant_uid from access.session_pharmacies sp"
              + " join access.sessions s on s.uid = sp.session_uid where s.id = :id",
      nativeQuery = true)
  List<UUID> enteredPharmacies(@Param("id") Long id);

  /**
   * Records that session {@code sessionUid} works in {@code tenantUid}; 1 the first time, else 0.
   */
  @Modifying
  @Query(
      value =
          "insert into access.session_pharmacies (session_uid, tenant_uid, entered_at)"
              + " values (:session, :tenant, :at) on conflict do nothing",
      nativeQuery = true)
  int enter(
      @Param("session") UUID sessionUid, @Param("tenant") UUID tenantUid, @Param("at") Instant at);
}
