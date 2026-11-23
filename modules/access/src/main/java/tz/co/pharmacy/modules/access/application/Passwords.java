package tz.co.pharmacy.modules.access.application;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Argon2id password hashes and the password rule (NIST SP 800-63B §3.1.1.2, OWASP Password Storage
 * Cheat Sheet).
 *
 * <ul>
 *   <li>8 to 128 characters, counted as characters (code points), any characters allowed, no
 *       composition rules.
 *   <li>Not a commonly used or easily guessed password: a blocklist of the most common ones, one
 *       character repeated, a run like {@code 12345678} or {@code abcdefgh}, or the account's own
 *       email name.
 *   <li>Argon2id with 19 MiB, 2 iterations, 1 lane (OWASP's minimum). Hashes made with the older
 *       settings still verify: the settings are stored in each hash.
 *   <li>At most a few hashes at once per server, so a burst of sign-ins or password changes cannot
 *       use up the memory and CPU every pharmacy shares (adversarial review AUTH-6). This is a
 *       per-instance guard on local resources, not shared state, so it holds with several instances
 *       too.
 * </ul>
 */
@Component
class Passwords {

  private static final int MIN_LENGTH = 8;
  private static final int MAX_LENGTH = 128;

  /** Among the most used passwords in breach corpora, lower case. */
  static final Set<String> COMMON =
      Set.of(
          "password",
          "password1",
          "password12",
          "password123",
          "passw0rd",
          "p@ssw0rd",
          "p@ssword",
          "pa$$word",
          "12345678",
          "123456789",
          "1234567890",
          "0987654321",
          "11111111",
          "00000000",
          "88888888",
          "12341234",
          "87654321",
          "qwertyui",
          "qwerty123",
          "qwertyuiop",
          "1q2w3e4r",
          "1qaz2wsx",
          "zaq12wsx",
          "asdfghjk",
          "asdfghjkl",
          "zxcvbnm1",
          "iloveyou",
          "iloveyou1",
          "sunshine",
          "princess",
          "football",
          "baseball",
          "welcome1",
          "welcome123",
          "admin123",
          "administrator",
          "letmein1",
          "trustno1",
          "superman",
          "batman123",
          "monkey123",
          "dragon12",
          "master12",
          "computer",
          "internet",
          "whatever",
          "starwars",
          "abc12345",
          "abcd1234",
          "aa123456",
          "123qwe123",
          "changeme",
          "changeme1",
          "default1",
          "secret12",
          "test1234",
          "testtest",
          "pharmacy",
          "pharmacy1",
          "pharmacy123",
          "pharmacist",
          "dawa1234",
          "duka1234",
          "tanzania",
          "tanzania1",
          "tanzania123",
          "bongo123",
          "karibu123",
          "jambo123",
          "mungu123",
          "yesu1234",
          "allah123",
          "nakupenda",
          "mpenzi123",
          "simba123",
          "yanga123",
          "123456aa",
          "q1w2e3r4",
          "a1b2c3d4",
          "password!",
          "welcome!",
          "Passw0rd!".toLowerCase());

  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);

  /** Hashes running at once on this server; more wait briefly, then are told to try again. */
  private final Semaphore slots =
      new Semaphore(Math.max(2, Runtime.getRuntime().availableProcessors()));

  /** A hash to compare against when no user matches, so a miss takes as long as a hit. */
  private final String decoy = encoder.encode("decoy-password-for-timing");

  /** The hash of a new password, refused if the password breaks the rule. */
  String hash(String password, String field) {
    return hash(password, field, null);
  }

  /** The same, also refusing a password made of the account's email name ({@code email}). */
  String hash(String password, String field, String email) {
    String accepted = acceptable(password, field, email);
    return limited(() -> encoder.encode(accepted));
  }

  boolean matches(String password, String hash) {
    if (password == null) {
      return false;
    }
    return limited(() -> encoder.matches(password, hash == null ? decoy : hash));
  }

  static String acceptable(String password, String field) {
    return acceptable(password, field, null);
  }

  static String acceptable(String password, String field, String email) {
    int length = password == null ? 0 : password.codePointCount(0, password.length());
    if (password == null || length < MIN_LENGTH || length > MAX_LENGTH) {
      throw Refusal.invalid(
          "weak_password", "The password must be 8 to 128 characters long.", field);
    }
    String lower = password.toLowerCase(Locale.ROOT);
    if (COMMON.contains(lower) || repeated(lower) || run(lower) || ownName(lower, email)) {
      throw Refusal.invalid(
          "common_password",
          "That password is too easy to guess; choose one that is not a common word, a run of"
              + " keys or your own name.",
          field);
    }
    return password;
  }

  /** One character over and over: {@code aaaaaaaa}. */
  private static boolean repeated(String lower) {
    return lower.chars().distinct().count() == 1;
  }

  /** Each character one up or one down from the last: {@code 12345678}, {@code hgfedcba}. */
  private static boolean run(String lower) {
    int step = lower.charAt(1) - lower.charAt(0);
    if (Math.abs(step) != 1) {
      return false;
    }
    for (int i = 2; i < lower.length(); i++) {
      if (lower.charAt(i) - lower.charAt(i - 1) != step) {
        return false;
      }
    }
    return true;
  }

  /** The part of the email before the @ (or the staff username), when it is 4 letters or more. */
  private static boolean ownName(String lower, String email) {
    if (email == null) {
      return false;
    }
    String name = email.toLowerCase(Locale.ROOT).split("@")[0].replaceAll("[^a-z0-9]", "");
    return name.length() >= 4 && lower.replaceAll("[^a-z0-9]", "").contains(name);
  }

  private <T> T limited(java.util.function.Supplier<T> work) {
    boolean acquired;
    try {
      acquired = slots.tryAcquire(10, TimeUnit.SECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      acquired = false;
    }
    if (!acquired) {
      throw Refusal.tooManyRequests(
          "The server is busy checking passwords; try again in a moment.", 5);
    }
    try {
      return work.get();
    } finally {
      slots.release();
    }
  }
}
