package tz.co.pharmacy.modules.access.ports.inbound;

/**
 * Forgotten passwords: a link by email, valid 30 minutes and once. Requesting one never reveals
 * whether the email has an account.
 */
public interface PasswordResetService {

  void request(String email, ClientInfo client);

  /**
   * Sets the new password and signs the user out everywhere. 422 invalid_token (unknown, used or
   * expired); 422 weak_password.
   */
  void complete(String token, String newPassword);
}
