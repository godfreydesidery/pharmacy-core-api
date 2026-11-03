package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.MutableEntity;
import tz.co.pharmacy.platform.persistence.audit.AuditIgnore;

/**
 * The second step of a sign-in: the password was right and a second factor is due. It lasts a few
 * minutes, takes a few attempts, and is consumed by the session it opens.
 */
@Getter
@Entity
@AuditIgnore
@Table(schema = "access", name = "sign_in_challenges")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SignInChallenge extends MutableEntity {

  private String tokenHash;
  private UUID userUid;
  private Instant expiresAt;
  private int attempts;
  private Instant consumedAt;
  private String ip;
  private String userAgent;

  public SignInChallenge(
      String tokenHash, UUID userUid, Instant expiresAt, String ip, String userAgent) {
    this.tokenHash = tokenHash;
    this.userUid = userUid;
    this.expiresAt = expiresAt;
    this.ip = ip;
    this.userAgent = userAgent;
  }

  public boolean openAt(Instant at, int maxAttempts) {
    return consumedAt == null && expiresAt.isAfter(at) && attempts < maxAttempts;
  }

  public void attempt() {
    attempts++;
  }

  public void consume(Instant at) {
    consumedAt = at;
  }
}
