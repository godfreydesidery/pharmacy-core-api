package tz.co.pharmacy.support;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** The authenticator app of a test: RFC 6238 codes (SHA-1, 30 s, 6 digits) of a base32 secret. */
public final class Totp {

  private Totp() {}

  public static String code(String base32Secret, Instant at) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(base32(base32Secret), "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(at.getEpochSecond() / 30).array());
      int offset = hash[hash.length - 1] & 0xf;
      int binary = ByteBuffer.wrap(hash, offset, 4).getInt() & 0x7fffffff;
      return String.format("%06d", binary % 1_000_000);
    } catch (GeneralSecurityException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static byte[] base32(String value) {
    String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    String clean = value.replace("=", "").replace(" ", "").toUpperCase();
    ByteBuffer out = ByteBuffer.allocate(clean.length() * 5 / 8);
    int buffer = 0;
    int bits = 0;
    for (char c : clean.toCharArray()) {
      buffer = (buffer << 5) | alphabet.indexOf(c);
      bits += 5;
      if (bits >= 8) {
        out.put((byte) (buffer >> (bits - 8)));
        bits -= 8;
      }
    }
    return out.array();
  }
}
