package tz.co.pharmacy.platform.persistence.audit;

import java.time.temporal.TemporalAccessor;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import tz.co.pharmacy.platform.persistence.RecordedEntity;

/**
 * Turns entity property values into what the audit trail stores: plain JSON values, a referenced
 * record as its uid, and every secret masked. A property is a secret when its name contains
 * password, secret, token, hash, recovery, credential or key material (publicKey, privateKey).
 */
public final class AuditValues {

  /** Properties that name a record, best first. */
  private static final List<String> LABELS =
      List.of(
          "number", "name", "email", "code", "memberNumber", "reference", "templateKey", "title");

  /** A human label from the record's values (e.g. its number or name), or null. */
  static String label(Map<String, Object> values, Map<String, Object> fallback) {
    for (Map<String, Object> source : java.util.Arrays.asList(values, fallback)) {
      if (source == null) {
        continue;
      }
      for (String key : LABELS) {
        Object value = source.get(key);
        if (value != null && !String.valueOf(value).isBlank() && !MASK.equals(value)) {
          String text = String.valueOf(value);
          return text.length() > 120 ? text.substring(0, 120) : text;
        }
      }
    }
    return null;
  }

  public static final String MASK = "***";

  /**
   * Bookkeeping properties: those every entity carries (the entry itself records who and when), and
   * usage counters that change on every sign-in or use and say nothing about a change.
   */
  static final Set<String> IGNORED =
      Set.of(
          "updatedAt",
          "updatedBy",
          "version",
          "createdAt",
          "createdBy",
          "tenantUid",
          "id",
          "lastSignInAt",
          "lastSeenAt",
          "lastUsedAt",
          "failedSignIns",
          "firstFailedAt",
          "totpLastStep",
          "signatureCount");

  private static final String[] SECRET_MARKERS = {
    "password",
    "secret",
    "token",
    "hash",
    "recovery",
    "credential",
    "publickey",
    "privatekey",
    "otp"
  };
  private static final int MAX_TEXT = 500;

  private AuditValues() {}

  public static boolean isSecret(String property) {
    String name = property.toLowerCase(Locale.ROOT);
    for (String marker : SECRET_MARKERS) {
      if (name.contains(marker)) {
        return true;
      }
    }
    return false;
  }

  /** The JSON-safe value of one property, masked when it is a secret. */
  public static Object value(String property, Object value) {
    if (value == null) {
      return null;
    }
    if (isSecret(property)) {
      return MASK;
    }
    return plain(value);
  }

  /** Masks the secrets of a whole map, such as the before or after of an explicit event. */
  public static Map<String, Object> masked(Map<String, Object> values) {
    if (values == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    values.forEach((key, value) -> out.put(key, value(key, value)));
    return out;
  }

  static Object plain(Object value) {
    return switch (value) {
      case String text -> text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
      case Number number -> number;
      case Boolean flag -> flag;
      case UUID uid -> uid.toString();
      case Enum<?> constant -> constant.name();
      case TemporalAccessor time -> time.toString();
      case RecordedEntity entity -> entity.getUid() == null ? null : entity.getUid().toString();
      case Collection<?> items -> "[" + items.size() + " items]";
      case Map<?, ?> entries -> "[" + entries.size() + " entries]";
      case byte[] bytes -> "[" + bytes.length + " bytes]";
      default -> {
        String text = String.valueOf(value);
        yield text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
      }
    };
  }
}
