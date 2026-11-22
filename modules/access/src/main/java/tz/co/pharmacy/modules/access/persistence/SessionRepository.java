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
   * The person used the app: the inactivity clock restarts, with the limit of the pharmacy they
   * work in. Without a version bump or an audit entry; the web app calls it at most every half
   * minute.
   */
  @Modifying
  @Query(
      "update Session s set s.activeAt = :at, s.lastSeenAt = :at, s.idleMinutes = :idleMinutes"
          + " where s.id = :id")
  int markActive(
      @Param("id") Long id, @Param("at") Instant at, @Param("idleMinutes") Integer idleMinutes);

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
