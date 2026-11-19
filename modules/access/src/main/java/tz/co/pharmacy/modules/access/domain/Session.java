package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
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

  public Session(
      String tokenHash,
      UUID userUid,
      Instant expiresAt,
      String ip,
      String userAgent,
      String method) {
    this.tokenHash = tokenHash;
    this.userUid = userUid;
    this.expiresAt = expiresAt;
    this.ip = ip;
    this.userAgent = userAgent;
    this.method = method;
    this.lastSeenAt = Clocks.now();
  }

  public boolean liveAt(Instant at) {
    return revokedAt == null && expiresAt.isAfter(at);
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
