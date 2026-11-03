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

/** A password reset token: its SHA-256 hash, valid for a short while and once only. */
@Getter
@Entity
@AuditIgnore
@Table(schema = "access", name = "password_resets")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PasswordReset extends MutableEntity {

  private String tokenHash;
  private UUID userUid;
  private Instant expiresAt;
  private Instant usedAt;
  private String requestedIp;

  public PasswordReset(String tokenHash, UUID userUid, Instant expiresAt, String requestedIp) {
    this.tokenHash = tokenHash;
    this.userUid = userUid;
    this.expiresAt = expiresAt;
    this.requestedIp = requestedIp;
  }

  public boolean usableAt(Instant at) {
    return usedAt == null && expiresAt.isAfter(at);
  }

  public void use(Instant at) {
    usedAt = at;
  }
}
