package tz.co.pharmacy.modules.access.application;

import java.security.SecureRandom;

/**
 * Temporary passwords an admin reads out at the counter or sends by SMS: sixteen capitals and
 * digits in four groups (K7PM-4RXA-CDEF-GH23), leaving out the ones easily mistaken for each other
 * (0 O, 1 I L, 5 S, 8 B). About 76 bits, and at least the 15 characters a password needs when it is
 * the only factor (STD-21), for one sign-in before the person chooses their own.
 */
final class TemporaryPasswords {

  private static final char[] ALPHABET = "ACDEFGHJKMNPQRTUVWXYZ234679".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();

  /** Characters drawn, dashes aside. */
  static final int LENGTH = 16;

  private TemporaryPasswords() {}

  static String next() {
    StringBuilder password = new StringBuilder(19);
    for (int i = 0; i < LENGTH; i++) {
      if (i > 0 && i % 4 == 0) {
        password.append('-');
      }
      password.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
    }
    return password.toString();
  }
}
