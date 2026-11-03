package tz.co.pharmacy.modules.files.storage;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Signs and checks the local store's presigned URLs: HMAC-SHA256 over the method, the object key,
 * the expiry (Unix seconds) and the content type, so a URL cannot be reused for another object,
 * another method, another type or after it expired.
 */
public final class UrlSigner {

  private final byte[] secret;
  private final Clock clock;

  public UrlSigner(String secret, Clock clock) {
    if (secret == null || secret.length() < 16) {
      throw new IllegalArgumentException("pharmacy.files.signing-secret must be 16+ characters");
    }
    this.secret = secret.getBytes(StandardCharsets.UTF_8);
    this.clock = clock;
  }

  public String sign(String method, String key, long expires, String contentType) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      String payload = method + "\n" + key + "\n" + expires + "\n" + nullToEmpty(contentType);
      return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** Refuses a wrong signature (403 invalid_signature) or an expired URL (403 url_expired). */
  public void verify(
      String method, String key, long expires, String contentType, String signature) {
    String expected = sign(method, key, expires, contentType);
    boolean same =
        signature != null
            && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    if (!same) {
      throw Refusal.forbidden("invalid_signature", "This file link is not valid.");
    }
    if (clock.instant().getEpochSecond() > expires) {
      throw Refusal.forbidden("url_expired", "This file link has expired; request a new one.");
    }
  }

  public long expiresIn(java.time.Duration ttl) {
    return clock.instant().plus(ttl).getEpochSecond();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }
}
