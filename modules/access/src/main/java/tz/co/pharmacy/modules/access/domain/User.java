package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.MutableEntity;

/**
 * A person who signs in. Users are platform-wide: one email, one password, memberships in any
 * number of tenants. A root administrator acts above every tenant. A user may add a second factor
 * (a TOTP authenticator app) and passkeys; repeated failed sign-ins lock the account for a while.
 *
 * <p>A <em>managed login</em> belongs to one pharmacy, whose admin created it: it signs in as
 * {@code username@CODE} (held in {@code email}, lower case), mail goes to its {@code contactEmail},
 * and that admin may reset its password. Every other account belongs to the person.
 */
@Getter
@Entity
@Table(schema = "access", name = "users")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User extends MutableEntity {

  private String email;
  private String name;
  private String passwordHash;
  private boolean rootAdmin;
  private boolean active;

  /** E.164, such as +255754000111; null when not given. */
  private String phone;

  /** When the holder of {@code phone} typed the code sent to it; null while unverified. */
  private Instant phoneVerifiedAt;

  /** A staff login its person linked to their personal account: that account, and when. */
  private UUID linkedToUid;

  private Instant linkedAt;

  private Instant lastSignInAt;

  private boolean twoFactorEnabled;

  /** The TOTP secret, encrypted (AES-GCM); null until set up. */
  private String totpSecret;

  /** The last TOTP time step accepted, so a code is never accepted twice. */
  private Long totpLastStep;

  private Instant twoFactorEnabledAt;
  private int failedSignIns;
  private Instant firstFailedAt;
  private Instant lockedUntil;

  /** The WebAuthn user handle (base64url of 32 random bytes), set with the first passkey. */
  private String webauthnUserHandle;

  /** The pharmacy a managed login belongs to; null for a person's own account. */
  private UUID managedByTenantUid;

  /** Where mail to a managed login goes; never used to sign in. */
  private String contactEmail;

  /** The person must choose their own password before working. */
  private boolean passwordChangeRequired;

  public User(String email, String name, String passwordHash, boolean rootAdmin) {
    this.email = email;
    this.name = name;
    this.passwordHash = passwordHash;
    this.rootAdmin = rootAdmin;
    this.active = true;
  }

  public void signedIn(Instant at) {
    lastSignInAt = at;
    failedSignIns = 0;
    firstFailedAt = null;
    lockedUntil = null;
  }

  /** The person's own name and phone. A different phone is no longer verified. */
  public void changeProfile(String name, String phone) {
    this.name = name;
    if (phone == null ? this.phone != null : !phone.equals(this.phone)) {
      this.phoneVerifiedAt = null;
    }
    this.phone = phone;
  }

  /** The person's name only (their phone changes through verification). */
  public void rename(String name) {
    this.name = name;
  }

  /** The holder of {@code phone} proved it with a code: it is this account's verified phone. */
  public void verifyPhone(String phone, Instant at) {
    this.phone = phone;
    this.phoneVerifiedAt = at;
  }

  /** The phone was verified by another account since (a recycled number): it is dropped here. */
  public void losePhone() {
    this.phone = null;
    this.phoneVerifiedAt = null;
  }

  public boolean hasVerifiedPhone() {
    return phone != null && phoneVerifiedAt != null;
  }

  /** This staff login now belongs to {@code personalUid}: it is off for good. */
  public void linkTo(UUID personalUid, Instant at) {
    this.linkedToUid = personalUid;
    this.linkedAt = at;
    this.active = false;
  }

  /** A new sign-in email, confirmed from its mailbox. */
  public void changeEmail(String email) {
    this.email = email;
  }

  public void activate(boolean active) {
    this.active = active;
  }

  /** No longer a root administrator; the account itself remains for any memberships. */
  public void revokeRoot() {
    this.rootAdmin = false;
  }

  /** The person's own new password: any requirement to change it is met. */
  public void changePassword(String passwordHash) {
    this.passwordHash = passwordHash;
    this.passwordChangeRequired = false;
  }

  /** A new managed login of {@code tenantUid}, with a temporary password to change at sign-in. */
  public static User managed(
      UUID tenantUid, String signIn, String name, String temporaryPasswordHash) {
    User user = new User(signIn, name, temporaryPasswordHash, false);
    user.managedByTenantUid = tenantUid;
    user.passwordChangeRequired = true;
    return user;
  }

  public boolean isManaged() {
    return managedByTenantUid != null;
  }

  /** Whether this is a managed login of {@code tenantUid}. */
  public boolean isManagedBy(UUID tenantUid) {
    return managedByTenantUid != null && managedByTenantUid.equals(tenantUid);
  }

  /** The admin of the managed login's pharmacy set a temporary password: change it at sign-in. */
  public void resetByAdmin(String temporaryPasswordHash) {
    if (!isManaged()) {
      throw new IllegalStateException("only a managed login's password is reset by an admin");
    }
    this.passwordHash = temporaryPasswordHash;
    this.passwordChangeRequired = true;
    unlock();
  }

  /** The contact details of a managed login, kept by its pharmacy. */
  public void changeManagedContact(String name, String phone, String contactEmail) {
    if (!isManaged()) {
      throw new IllegalStateException("a person's own details are changed by the person");
    }
    this.name = name;
    this.phone = phone;
    this.contactEmail = contactEmail;
  }

  /** Where mail to this person goes: their email, or a managed login's contact email. */
  public String mailbox() {
    return isManaged() ? contactEmail : email;
  }

  /** Whether the account is locked at {@code at}. */
  public boolean lockedAt(Instant at) {
    return lockedUntil != null && lockedUntil.isAfter(at);
  }

  /**
   * Counts a failed sign-in in a window of {@code window}; at {@code maxFailures} the account locks
   * for {@code lockFor}. Returns whether this failure locked it.
   */
  public boolean failedSignIn(Instant at, int maxFailures, Duration window, Duration lockFor) {
    if (firstFailedAt == null || firstFailedAt.plus(window).isBefore(at)) {
      firstFailedAt = at;
      failedSignIns = 0;
    }
    failedSignIns++;
    if (failedSignIns >= maxFailures) {
      lockedUntil = at.plus(lockFor);
      failedSignIns = 0;
      firstFailedAt = null;
      return true;
    }
    return false;
  }

  public void unlock() {
    lockedUntil = null;
    failedSignIns = 0;
    firstFailedAt = null;
  }

  /** Stores a new, not yet confirmed, TOTP secret. */
  public void pendingTotpSecret(String encryptedSecret) {
    if (twoFactorEnabled) {
      throw new IllegalStateException("two-factor is already on");
    }
    this.totpSecret = encryptedSecret;
    this.totpLastStep = null;
  }

  public void enableTwoFactor(Instant at, long step) {
    this.twoFactorEnabled = true;
    this.twoFactorEnabledAt = at;
    this.totpLastStep = step;
  }

  public void disableTwoFactor() {
    this.twoFactorEnabled = false;
    this.twoFactorEnabledAt = null;
    this.totpSecret = null;
    this.totpLastStep = null;
  }

  /** Records the accepted TOTP step; false when it is not newer than the last one (a replay). */
  public boolean acceptTotpStep(long step) {
    if (totpLastStep != null && step <= totpLastStep) {
      return false;
    }
    totpLastStep = step;
    return true;
  }

  public void assignWebauthnUserHandle(String handle) {
    if (webauthnUserHandle == null) {
      webauthnUserHandle = handle;
    }
  }
}
