package tz.co.pharmacy.modules.access.application;

import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The rule that an account's password may stand alone only when it has 15 characters (STD-21):
 * applied where a second factor is taken away.
 */
final class SecondFactors {

  private SecondFactors() {}

  /**
   * Refuses to leave the account with its password as the only factor unless that password is known
   * to have 15 characters (409 password_too_short_without_two_factor, with {@code minLength}). The
   * person changes the password first, then turns two-factor off: one clear step each, and the
   * password is never typed where the code is.
   */
  static void passwordMayStandAlone(User user) {
    if (!Boolean.TRUE.equals(user.getPasswordLongEnoughAlone())) {
      throw Refusal.conflict(
              "password_too_short_without_two_factor",
              "Without two-factor the password must have at least "
                  + Passwords.MIN_LENGTH_ALONE
                  + " characters. Change your password to one of "
                  + Passwords.MIN_LENGTH_ALONE
                  + " characters or more first, then turn two-factor off.")
          .with("minLength", Passwords.MIN_LENGTH_ALONE);
    }
  }
}
