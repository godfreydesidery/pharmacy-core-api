package tz.co.pharmacy.modules.access.application;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Argon2id password hashes, and the password rule: at least 8 characters, at most 128. */
@Component
class Passwords {

  private static final int MIN_LENGTH = 8;
  private static final int MAX_LENGTH = 128;

  private final Argon2PasswordEncoder encoder =
      Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();

  /** A hash to compare against when no user matches, so a miss takes as long as a hit. */
  private final String decoy = encoder.encode("decoy-password-for-timing");

  String hash(String password, String field) {
    return encoder.encode(acceptable(password, field));
  }

  boolean matches(String password, String hash) {
    if (password == null) {
      return false;
    }
    return encoder.matches(password, hash == null ? decoy : hash);
  }

  static String acceptable(String password, String field) {
    if (password == null || password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
      throw Refusal.invalid(
          "weak_password", "The password must be 8 to 128 characters long.", field);
    }
    return password;
  }
}
