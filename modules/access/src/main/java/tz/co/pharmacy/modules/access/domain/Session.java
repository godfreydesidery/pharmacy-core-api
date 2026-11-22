package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.persistence.MutableEntity;
import tz.co.pharmacy.platform.persistence.audit.AuditIgnore;

/**
 * A signed-in session. Only the SHA-256 hash of its bearer token is stored, so a copy of the table
 * gives no usable token; a session ends when it expires or is revoked. It records the client's IP
 * and user agent, how it was opened (PASSWORD, TWO_FACTOR, PASSKEY, INVITATION) and when it was
 * last used. Its sign-in and revocation are audited explicitly, so its rows are not.
 *
 * <p>It also ends after {@code idleMinutes} without use: {@code activeAt} is when the person last
 * used the app (reported by the web app on a key, click or touch), not when a request last came, as
 * background requests run with nobody at the screen. Null in either means no inactivity limit.
 */
@Getter
@Entity
@AuditIgnore
@Table(schema = "access", name = "sessions")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Session extends MutableEntity {

  private String tokenHash;
  private UUID userUid;
  private Instant expiresAt;
  private Instant revokedAt;
  private String ip;
  private String userAgent;
  private Instant lastSeenAt;
  private String method;
  private Instant activeAt;
  private Integer idleMinutes;

  public Session(
      String tokenHash,
      UUID userUid,
      Instant expiresAt,
      String ip,
      String userAgent,
      String method,
      Integer idleMinutes) {
    this.tokenHash = tokenHash;
    this.userUid = userUid;
    this.expiresAt = expiresAt;
    this.ip = ip;
    this.userAgent = userAgent;
    this.method = method;
    this.lastSeenAt = Clocks.now();
    this.activeAt = this.lastSeenAt;
    this.idleMinutes = idleMinutes;
  }

  public boolean liveAt(Instant at) {
    return revokedAt == null && expiresAt.isAfter(at);
  }

  /** Whether it has gone unused for its inactivity limit by {@code at}. */
  public boolean idleAt(Instant at) {
    return activeAt != null
        && idleMinutes != null
        && !activeAt.plus(Duration.ofMinutes(idleMinutes)).isAfter(at);
  }

  /** When it ends for want of use, unless used again; null without an inactivity limit. */
  public Instant idleEndsAt() {
    return activeAt == null || idleMinutes == null
        ? null
        : activeAt.plus(Duration.ofMinutes(idleMinutes));
  }

  public void revoke(Instant at) {
    if (revokedAt == null) {
      revokedAt = at;
    }
  }

  public void seen(Instant at) {
    lastSeenAt = at;
  }
}
