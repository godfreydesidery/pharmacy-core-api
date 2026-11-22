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

  /** To the old address (and the verified phone): the sign-in email is now {@code newEmail}. */
  void emailChanged(
      UUID userUid, String name, String oldEmail, String phone, String newEmail, Instant at);

  /** To the mailbox (and the verified phone): the password was changed or reset. */
  void passwordChanged(UUID userUid, String name, String email, String phone, Instant at);

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
