package tz.co.pharmacy.modules.access.application;

import java.security.SecureRandom;

/**
 * Temporary passwords an admin reads out at the counter or sends by SMS: eight capitals and digits
 * in two groups (K7PM-4RXA), leaving out the ones easily mistaken for each other (0 O, 1 I L, 5 S,
 * 8 B). About 37 bits, for one sign-in before the person chooses their own.
 */
final class TemporaryPasswords {

  private static final char[] ALPHABET = "ACDEFGHJKMNPQRTUVWXYZ234679".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  private TemporaryPasswords() {}

  static String next() {
    StringBuilder password = new StringBuilder(9);
    for (int i = 0; i < 8; i++) {
      if (i == 4) {
        password.append('-');
      }
      password.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
    }
    return password.toString();
  }
}
