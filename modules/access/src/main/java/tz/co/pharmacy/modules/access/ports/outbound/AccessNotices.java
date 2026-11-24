package tz.co.pharmacy.modules.access.ports.outbound;

import java.time.Instant;
import java.util.UUID;

/**
 * The messages access sends: a password reset link, an invitation and a new-device sign-in notice.
 * Composition binds it to the notifications module; each is queued in the caller's transaction.
 */
public interface AccessNotices {

  void passwordReset(UUID userUid, String name, String email, String link, int validMinutes);

  /** Sent in the inviting tenant's scope. */
  void invitation(
      UUID tenantUid,
      String name,
      String email,
      String inviterName,
      String roleName,
      String link,
      int validDays);

  void newSignIn(UUID userUid, String name, String email, String device, String ip, Instant at);

  /** A code to type, by SMS to {@code phone} (which need not be verified yet). */
  void verificationCode(UUID userUid, String name, String phone, String code, int validMinutes);

  /** The link that confirms a new sign-in email, to that new address. */
  void emailConfirm(UUID userUid, String name, String newEmail, String link, int validMinutes);

  /** To an address someone tried to take for an account while it already has one. */
  void emailInUse(String email);

  /**
   * To the old address (and the verified phone): the sign-in email is now {@code newEmail}. With
   * {@code revertLink} (not after a recovery), the link that undoes it for {@link
   * tz.co.pharmacy.modules.access.ports.inbound.AccountSecurity#EMAIL_REVERT_DAYS} days (AUTH-13).
   */
  void emailChanged(
      UUID userUid,
      String name,
      String oldEmail,
      String phone,
      String newEmail,
      Instant at,
      String revertLink);

  /** To the mailbox (and the verified phone): the password was changed or reset. */
  void passwordChanged(UUID userUid, String name, String email, String phone, Instant at);

  /** What changed in the security of an account, to tell its owner on their other channels. */
  enum SecurityChange {
    PASSKEY_ADDED,
    TWO_FACTOR_ON,
    TWO_FACTOR_OFF,
    PHONE_VERIFIED
  }

  /**
   * A second way into the account was added or one was removed: told by email and, when there is a
   * verified phone, SMS (NIST SP 800-63B §6.1.2.3), so a stolen session cannot quietly keep a way
   * back in (adversarial review AUTH-1). {@code phone} is masked for the email.
   */
  void securityChanged(
      UUID userUid, String name, String email, String phone, SecurityChange change, Instant at);

  /** To the earlier holder of a phone that another account has now verified. */
  void phoneRemoved(UUID userUid, String name, String email, String phone);

  /** To the old address and verified phone: a recovery to {@code newEmail} was started. */
  void recoveryStarted(
      UUID userUid,
      String name,
      String oldEmail,
      String phone,
      String newEmail,
      Instant usableAt,
      String cancelLink);

  /** To the person: a pharmacy staff login ({@code signIn}) is now part of their account. */
  void loginLinked(UUID userUid, String name, String email, String signIn, Instant at);

  /** To the new address: the link that completes a recovery, usable from {@code usableAt}. */
  void recoveryLink(String name, String newEmail, String link, Instant usableAt);
}
