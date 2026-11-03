package tz.co.pharmacy.modules.access.application;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Time-based one-time passwords (RFC 6238 over RFC 4226 HOTP): HMAC-SHA1, 30-second steps, 6
 * digits, accepting the previous and the next step for clock drift. Secrets travel in base32 (RFC
 * 4648), as authenticator apps expect.
 */
final class Totp {

  static final int STEP_SECONDS = 30;
  static final int DIGITS = 6;
  private static final int WINDOW = 1;
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();

  private Totp() {}

  /** A new 160-bit secret. */
  static byte[] newSecret() {
    byte[] secret = new byte[20];
    RANDOM.nextBytes(secret);
    return secret;
  }

  static long step(Instant at) {
    return Math.floorDiv(at.getEpochSecond(), STEP_SECONDS);
  }

  /** The code of one step, with {@code digits} digits (6 here; RFC 6238's vectors use 8). */
  static String code(byte[] secret, long step, int digits) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secret, "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int offset = hash[hash.length - 1] & 0x0f;
      int binary =
          ((hash[offset] & 0x7f) << 24)
              | ((hash[offset + 1] & 0xff) << 16)
              | ((hash[offset + 2] & 0xff) << 8)
              | (hash[offset + 3] & 0xff);
      int modulo = (int) Math.pow(10, digits);
      return String.format("%0" + digits + "d", binary % modulo);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /**
   * The step whose code {@code code} is, within one step of {@code at}, or -1. Compares in constant
   * time.
   */
  static long match(byte[] secret, String code, Instant at) {
    if (code == null) {
      return -1;
    }
    String digits = code.replaceAll("\\s", "");
    if (!digits.matches("\\d{" + DIGITS + "}")) {
      return -1;
    }
    long now = step(at);
    long found = -1;
    for (long step = now - WINDOW; step <= now + WINDOW; step++) {
      if (java.security.MessageDigest.isEqual(
          code(secret, step, DIGITS).getBytes(), digits.getBytes())) {
        found = step;
      }
    }
    return found;
  }

  static String otpauthUri(String issuer, String account, String base32Secret) {
    String label = encode(issuer) + ":" + encode(account);
    return "otpauth://totp/"
        + label
        + "?secret="
        + base32Secret
        + "&issuer="
        + encode(issuer)
        + "&algorithm=SHA1&digits="
        + DIGITS
        + "&period="
        + STEP_SECONDS;
  }

  static String base32(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    int buffer = 0;
    int bits = 0;
    for (byte value : bytes) {
      buffer = (buffer << 8) | (value & 0xff);
      bits += 8;
      while (bits >= 5) {
        out.append(BASE32.charAt((buffer >> (bits - 5)) & 31));
        bits -= 5;
      }
    }
    if (bits > 0) {
      out.append(BASE32.charAt((buffer << (5 - bits)) & 31));
    }
    return out.toString();
  }

  static byte[] fromBase32(String text) {
    String clean = text.replace("=", "").replace(" ", "").toUpperCase(Locale.ROOT);
    ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
    int buffer = 0;
    int bits = 0;
    for (char c : clean.toCharArray()) {
      int value = BASE32.indexOf(c);
      if (value < 0) {
        throw new IllegalArgumentException("not base32: " + c);
      }
      buffer = (buffer << 5) | value;
      bits += 5;
      if (bits >= 8) {
        out.put((byte) ((buffer >> (bits - 8)) & 0xff));
        bits -= 8;
      }
    }
    return out.array();
  }

  private static String encode(String text) {
    return java.net.URLEncoder.encode(text, java.nio.charset.StandardCharsets.UTF_8)
        .replace("+", "%20");
  }
}
