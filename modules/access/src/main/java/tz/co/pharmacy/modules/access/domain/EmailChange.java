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
 * A change of sign-in email waiting for the new mailbox to confirm it. {@code CHANGE}: asked for by
 * the person. {@code RECOVERY}: asked for by a root admin for a person who lost their mailbox; it
 * can be used only from {@code notBefore}, and until then the person may cancel it from the notice
 * sent to their old email and phone. Token hashes only.
 */
@Getter
@Entity
@AuditIgnore
@Table(schema = "access", name = "email_changes")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EmailChange extends MutableEntity {

  public static final String CHANGE = "CHANGE";
  public static final String RECOVERY = "RECOVERY";

  private UUID userUid;
  private String kind;
  private String oldEmail;
  private String newEmail;
  private String tokenHash;
  private String cancelTokenHash;
  private Instant notBefore;
  private Instant expiresAt;
  private Instant usedAt;
  private Instant cancelledAt;
  private boolean resetTwoFactor;
  private String reason;
  private String requestedIp;

  public EmailChange(
      UUID userUid,
      String kind,
      String oldEmail,
      String newEmail,
      String tokenHash,
      String cancelTokenHash,
      Instant notBefore,
      Instant expiresAt,
      boolean resetTwoFactor,
      String reason,
      String requestedIp) {
    this.userUid = userUid;
    this.kind = kind;
    this.oldEmail = oldEmail;
    this.newEmail = newEmail;
    this.tokenHash = tokenHash;
    this.cancelTokenHash = cancelTokenHash;
    this.notBefore = notBefore;
    this.expiresAt = expiresAt;
    this.resetTwoFactor = resetTwoFactor;
    this.reason = reason;
    this.requestedIp = requestedIp;
  }

  /** Still open: not used, not cancelled, not expired. */
  public boolean openAt(Instant at) {
    return usedAt == null && cancelledAt == null && expiresAt.isAfter(at);
  }

  /** Open and past its hold, if it has one. */
  public boolean usableAt(Instant at) {
    return openAt(at) && (notBefore == null || !notBefore.isAfter(at));
  }

  public boolean isRecovery() {
    return RECOVERY.equals(kind);
  }

  public void use(Instant at) {
    usedAt = at;
  }

  public void cancel(Instant at) {
    cancelledAt = at;
  }
}
