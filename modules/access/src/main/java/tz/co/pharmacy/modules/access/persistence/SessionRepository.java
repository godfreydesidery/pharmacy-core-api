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

  @Modifying
  @Query(
      "update Session s set s.revokedAt = :at where s.userUid = :userUid and s.revokedAt is null"
          + " and (:keepHash is null or s.tokenHash <> :keepHash)")
  int revokeAll(
      @Param("userUid") UUID userUid, @Param("at") Instant at, @Param("keepHash") String keepHash);
}
