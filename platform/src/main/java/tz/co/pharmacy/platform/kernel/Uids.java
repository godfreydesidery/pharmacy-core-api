package tz.co.pharmacy.platform.kernel;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * Record uids: UUID version 7 (RFC 9562). The first 48 bits are the Unix time in milliseconds, so
 * new uids sort after older ones (keyset paging relies on it) and a POS can make them offline.
 */
public final class Uids {

  private static final SecureRandom RANDOM = new SecureRandom();

  private Uids() {}

  public static UUID newUid() {
    long milliseconds = System.currentTimeMillis();
    long randomA = RANDOM.nextLong();
    long randomB = RANDOM.nextLong();
    long mostSignificant = (milliseconds << 16) | 0x7000L | (randomA & 0x0FFFL);
    long leastSignificant = (randomB & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(mostSignificant, leastSignificant);
  }

  /** Parses a uid from input, refusing anything that is not one. */
  public static UUID parse(String value, String field) {
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(value.trim());
    } catch (IllegalArgumentException notAUid) {
      throw Refusal.invalid("invalid_" + field, field + " is not a uid.", field);
    }
  }
}
