package tz.co.pharmacy.modules.files.ports.inbound;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.RefusalKind;

/**
 * What may be stored and where: the accepted types and size, the owner types and purposes, and the
 * object key layout {@code tenants/{tenantUid}/{yyyy}/{mm}/{fileUid}}.
 */
public final class FileRules {

  public static final long MAX_SIZE = 10L * 1024 * 1024;

  public static final Set<String> CONTENT_TYPES =
      Set.of("image/png", "image/jpeg", "image/webp", "application/pdf");

  public static final Set<String> OWNER_TYPES =
      Set.of(
          "TENANT",
          "SALE",
          "PURCHASE_ORDER",
          "GOODS_RECEIPT",
          "PRODUCT",
          "CUSTOMER",
          "SUPPLIER",
          "EXPENSE",
          "OTHER");

  public static final Set<String> PURPOSES =
      Set.of("LOGO", "PRESCRIPTION", "INVOICE", "PHOTO", "ATTACHMENT");

  private FileRules() {}

  /** The accepted content type, normalised, or 415 unsupported_type. */
  public static String contentType(String contentType) {
    String type =
        contentType == null
            ? ""
            : contentType
                .split(";")[0]
                .trim()
                .toLowerCase(Locale.ROOT)
                .replace("image/jpg", "image/jpeg");
    if (!CONTENT_TYPES.contains(type)) {
      throw new Refusal(
          RefusalKind.UNSUPPORTED_MEDIA_TYPE,
          "unsupported_type",
          "Only PNG, JPEG, WebP images and PDF documents may be uploaded.",
          "contentType");
    }
    return type;
  }

  /** The size, or 413 file_too_large / 422 invalid_size. */
  public static long size(long size) {
    if (size <= 0) {
      throw Refusal.invalid("invalid_size", "size must be more than zero.", "size");
    }
    if (size > MAX_SIZE) {
      throw new Refusal(
          RefusalKind.PAYLOAD_TOO_LARGE, "file_too_large", "A file may be at most 10 MB.", "size");
    }
    return size;
  }

  public static String ownerType(String ownerType) {
    String type = Checks.text(ownerType, "ownerType", 32).toUpperCase(Locale.ROOT);
    if (!OWNER_TYPES.contains(type)) {
      throw Refusal.invalid(
          "invalid_owner_type", "ownerType is not one of the accepted values.", "ownerType");
    }
    return type;
  }

  public static String purpose(String purpose) {
    if (purpose == null || purpose.isBlank()) {
      return "ATTACHMENT";
    }
    String value = purpose.trim().toUpperCase(Locale.ROOT);
    if (!PURPOSES.contains(value)) {
      throw Refusal.invalid(
          "invalid_purpose", "purpose is not one of the accepted values.", "purpose");
    }
    return value;
  }

  /** A display name without path parts or control characters, at most 200 characters. */
  public static String name(String name) {
    String base = Checks.text(name, "name", 500).replace('\\', '/');
    base = base.substring(base.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"]", "").trim();
    if (base.isEmpty()) {
      throw Refusal.invalid("invalid_name", "name is required.", "name");
    }
    return base.length() > 200 ? base.substring(base.length() - 200) : base;
  }

  /** {@code tenants/{tenantUid}/{yyyy}/{mm}/{fileUid}}. */
  public static String key(UUID tenantUid, Instant at, UUID fileUid) {
    var date = at.atZone(ZoneOffset.UTC);
    return "tenants/%s/%04d/%02d/%s"
        .formatted(tenantUid, date.getYear(), date.getMonthValue(), fileUid);
  }
}
