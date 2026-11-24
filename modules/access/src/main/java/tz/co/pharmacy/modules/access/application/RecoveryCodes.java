package tz.co.pharmacy.modules.access.application;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Recovery codes: 10 codes such as {@code k7mq-2xhz}, from an alphabet without look-alikes. Each
 * carries about 40 bits: few enough to try them all against a bare SHA-256 hash, so a code is
 * stored as a keyed hash with the {@code codes} pepper ({@link SecretBox#codeHash}, AUTH-16); codes
 * stored as SHA-256 before still match ({@link #legacyHash}). Entry ignores case and dashes.
 */
final class RecoveryCodes {

  static final int COUNT = 10;
  private static final String ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";
  private static final SecureRandom RANDOM = new SecureRandom();

  private RecoveryCodes() {}

  static List<String> generate() {
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < COUNT; i++) {
      StringBuilder code = new StringBuilder();
      for (int c = 0; c < 8; c++) {
        if (c == 4) {
          code.append('-');
        }
        code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
      }
      codes.add(code.toString());
    }
    return codes;
  }

  static String normalise(String code) {
    return code == null ? "" : code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
  }

  /** The code as it is stored now: a keyed hash. */
  static String hash(SecretBox secrets, String code) {
    return secrets.codeHash(text(code));
  }

  /** Every form the code may be stored as, the newest first. */
  static java.util.List<String> hashes(SecretBox secrets, String code) {
    return secrets.codeHashes(text(code), legacyHash(code));
  }

  /** The unsalted SHA-256 hash codes were stored as before keyed hashes. */
  static String legacyHash(String code) {
    return Tokens.hash(text(code));
  }

  private static String text(String code) {
    return "recovery:" + normalise(code);
  }

  static boolean looksLikeRecoveryCode(String code) {
    return normalise(code).matches("[a-z0-9]{8}");
  }
}
