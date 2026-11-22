package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/**
 * What a person controls of their own account, and the ways back in (gaps G3, G5, G9, G13):
 *
 * <ul>
 *   <li><b>Email change.</b> Asked for with the password (and a second-factor code when two-factor
 *       is on); confirmed from the new mailbox; the old address and the verified phone are told.
 *       The answer is the same whether or not the new address already has an account.
 *   <li><b>Phone verification.</b> A 6-digit code by SMS, valid {@link #CODE_MINUTES} minutes, five
 *       tries, three sends an hour. A verified number belongs to one account; numbers are recycled
 *       in Tanzania, so the latest to verify it takes it and the earlier account is told.
 *   <li><b>Reset by phone.</b> A code sent to the verified phone resets the password.
 *   <li><b>Assisted recovery.</b> A root admin, after checking who the person is, moves the account
 *       to a new email. It can be used only after a {@link #RECOVERY_HOLD_HOURS}-hour hold, during
 *       which the old email and phone are told and may cancel it.
 *   <li><b>Security history.</b> The person's own sign-ins, failures and changes.
 * </ul>
 *
 * Managed logins (a pharmacy's own staff logins) have none of these: their pharmacy keeps their
 * contact details and resets them (422 managed_login).
 */
public interface AccountSecurity {

  int CODE_MINUTES = 10;
  int EMAIL_LINK_MINUTES = 60;
  int RECOVERY_HOLD_HOURS = 24;
  int RECOVERY_VALID_HOURS = 72;

  /**
   * Sends a confirmation link to {@code newEmail}. 401 invalid_password; 422 invalid_code (two-
   * factor on and no valid code); 422 same_email; 422 managed_login. Asking again replaces an
   * earlier request.
   */
  void requestEmailChange(
      UUID userUid, String newEmail, String password, String code, ClientInfo client);

  /**
   * Completes a change or a recovery from its link. A recovery also needs {@code newPassword} (422
   * password_required) and can be used only after its hold (422 recovery_on_hold). 422
   * invalid_token; 409 email_taken when the address got an account meanwhile.
   */
  void confirmEmail(String token, String newPassword);

  /** Cancels a recovery from the link sent to the old email or phone. 422 invalid_token. */
  void cancelRecovery(String token);

  /** The new email waiting for confirmation, or null. */
  String pendingEmail(UUID userUid);

  /** Sends a code to {@code phone} to verify it. 422 invalid_phone; 429 too_many_codes. */
  void sendPhoneCode(UUID userUid, String phone, ClientInfo client);

  /** Verifies the phone with the code sent to it. 422 invalid_code. */
  void verifyPhone(UUID userUid, String code);

  /** Removes the phone, with the password. 401 invalid_password. */
  void removePhone(UUID userUid, String password);

  /**
   * Sends a reset code to an account's verified phone. Always the same answer, and nothing is sent
   * for a number that is not verified.
   */
  void requestPhoneReset(String phone, ClientInfo client);

  /**
   * Sets a new password with the code sent to the phone: other sessions end and the person is told.
   * 422 invalid_code.
   */
  void completePhoneReset(String phone, String code, String newPassword);

  /**
   * A root admin starts the recovery of a person who lost their mailbox, to {@code newEmail};
   * {@code reason} records how the person was identified. 422 managed_login; 422 same_email.
   */
  RecoveryView startRecovery(
      UUID userUid, String newEmail, boolean resetTwoFactor, String reason, ClientInfo client);

  /** A root admin looks up an account by its sign-in email, to recover it. 404 user_not_found. */
  UserView account(String email);

  /** The account's own security events, newest first. */
  Page<SecurityEventView> history(UUID userUid, PageQuery query);

  /** A started recovery: to which address, and from when the link works. */
  record RecoveryView(UUID uid, String newEmail, Instant usableFrom, Instant expiresAt) {}

  /**
   * One security event: {@code action} such as SIGN_IN, SIGN_IN_FAILED, PASSWORD_CHANGED,
   * EMAIL_CHANGED, PHONE_VERIFIED, TWO_FACTOR_ENABLED; where it came from when known.
   */
  record SecurityEventView(UUID uid, Instant at, String action, String ip, String device) {}
}
