package tz.co.pharmacy.modules.access.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import tz.co.pharmacy.platform.persistence.MutableEntity;

/**
 * A person who signs in. Users are platform-wide: one email, one password, memberships in any
 * number of tenants. A root administrator acts above every tenant. A user may add a second factor
 * (a TOTP authenticator app) and passkeys; repeated failed sign-ins lock the account for a while.
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

  /** The person's own name and phone. */
  public void changeProfile(String name, String phone) {
    this.name = name;
    this.phone = phone;
  }

  public void activate(boolean active) {
    this.active = active;
  }

  /** No longer a root administrator; the account itself remains for any memberships. */
  public void revokeRoot() {
    this.rootAdmin = false;
  }

  public void changePassword(String passwordHash) {
    this.passwordHash = passwordHash;
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
