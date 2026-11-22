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
 * A short code sent by SMS, to verify a phone ({@code VERIFY}) or to reset a password ({@code
 * RESET}). Only its hash is kept; it dies at its expiry, at its first use or after {@link
 * #MAX_ATTEMPTS} wrong tries.
 */
@Getter
@Entity
@AuditIgnore
@Table(schema = "access", name = "phone_codes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PhoneCode extends MutableEntity {

  public static final int MAX_ATTEMPTS = 5;

  private UUID userUid;
  private String purpose;
  private String phone;
  private String codeHash;
  private Instant expiresAt;
  private int attempts;
  private Instant usedAt;
  private String requestedIp;

  public PhoneCode(
      UUID userUid,
      String purpose,
      String phone,
      String codeHash,
      Instant expiresAt,
      String requestedIp) {
    this.userUid = userUid;
    this.purpose = purpose;
    this.phone = phone;
    this.codeHash = codeHash;
    this.expiresAt = expiresAt;
    this.requestedIp = requestedIp;
  }

  public boolean usableAt(Instant at) {
    return usedAt == null && attempts < MAX_ATTEMPTS && expiresAt.isAfter(at);
  }

  /** One try: true when {@code hash} is the code's; a wrong one counts against it. */
  public boolean tryCode(String hash, Instant at) {
    if (!usableAt(at)) {
      return false;
    }
    if (codeHash.equals(hash)) {
      usedAt = at;
      return true;
    }
    attempts++;
    return false;
  }
}
