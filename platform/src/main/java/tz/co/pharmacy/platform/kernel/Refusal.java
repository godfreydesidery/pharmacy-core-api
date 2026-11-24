package tz.co.pharmacy.platform.kernel;

/**
 * A rule refused the request. {@code reason} is a stable machine code, such as {@code
 * insufficient_stock}; {@code field} names the input it is about as a JSON path, such as {@code
 * lines[0].quantity}, or is null. The delivery layer turns it into an RFC 9457 problem.
 */
public class Refusal extends RuntimeException {

  private final RefusalKind kind;
  private final String reason;
  private final String field;

  /** Seconds before a retry may succeed, for LOCKED and TOO_MANY_REQUESTS; otherwise null. */
  private final Long retryAfterSeconds;

  /**
   * Extra members of the problem (RFC 9457 §3.2 extensions), such as the action and amount of an
   * {@code approval_required}: strings, numbers or booleans, so a client can put the values into
   * its own words (the product, the quantity available, the limit).
   */
  private final java.util.Map<String, Object> extras;

  public Refusal(RefusalKind kind, String reason, String detail, String field) {
    this(kind, reason, detail, field, null);
  }

  private Refusal(
      RefusalKind kind, String reason, String detail, String field, Long retryAfterSeconds) {
    this(kind, reason, detail, field, retryAfterSeconds, java.util.Map.of());
  }

  private Refusal(
      RefusalKind kind,
      String reason,
      String detail,
      String field,
      Long retryAfterSeconds,
      java.util.Map<String, Object> extras) {
    super(detail);
    this.kind = kind;
    this.reason = reason;
    this.field = field;
    this.retryAfterSeconds = retryAfterSeconds;
    this.extras = extras;
  }

  /**
   * The same refusal with an extra problem member {@code name}. An amount or a count stays a
   * number; a date or a time goes out as its ISO-8601 text ({@code 2026-11-23}, {@code
   * 2026-11-23T08:00:00Z}), an enum as its name, a uid as its text.
   */
  public Refusal with(String name, Object value) {
    java.util.Map<String, Object> more = new java.util.LinkedHashMap<>(extras);
    more.put(name, member(value));
    return new Refusal(
        kind,
        reason,
        getMessage(),
        field,
        retryAfterSeconds,
        java.util.Collections.unmodifiableMap(more));
  }

  public java.util.Map<String, Object> extras() {
    return extras;
  }

  private static Object member(Object value) {
    return switch (value) {
      case null -> null;
      case Number number -> number;
      case Boolean flag -> flag;
      case String text -> text;
      case Enum<?> constant -> constant.name();
      default -> value.toString();
    };
  }

  /** 423: locked for {@code retryAfterSeconds}, such as {@code account_locked}. */
  public static Refusal locked(String reason, String detail, long retryAfterSeconds) {
    return new Refusal(RefusalKind.LOCKED, reason, detail, null, Math.max(1, retryAfterSeconds));
  }

  /** 429: too many requests; retry after {@code retryAfterSeconds}. */
  public static Refusal tooManyRequests(String detail, long retryAfterSeconds) {
    return new Refusal(
        RefusalKind.TOO_MANY_REQUESTS,
        "too_many_requests",
        detail,
        null,
        Math.max(1, retryAfterSeconds));
  }

  /** 503: the server is at its limit of work in progress; retry after {@code retryAfterSeconds}. */
  public static Refusal unavailable(String reason, String detail, long retryAfterSeconds) {
    return new Refusal(
        RefusalKind.SERVICE_UNAVAILABLE, reason, detail, null, Math.max(1, retryAfterSeconds));
  }

  public Long retryAfterSeconds() {
    return retryAfterSeconds;
  }

  public static Refusal invalid(String reason, String detail) {
    return new Refusal(RefusalKind.INVALID, reason, detail, null);
  }

  public static Refusal invalid(String reason, String detail, String field) {
    return new Refusal(RefusalKind.INVALID, reason, detail, field);
  }

  public static Refusal notFound(String reason, String detail) {
    return new Refusal(RefusalKind.NOT_FOUND, reason, detail, null);
  }

  public static Refusal conflict(String reason, String detail) {
    return new Refusal(RefusalKind.CONFLICT, reason, detail, null);
  }

  public static Refusal forbidden(String reason, String detail) {
    return new Refusal(RefusalKind.FORBIDDEN, reason, detail, null);
  }

  public static Refusal unauthenticated(String reason, String detail) {
    return new Refusal(RefusalKind.UNAUTHENTICATED, reason, detail, null);
  }

  /** 412 precondition_failed: the {@code If-Match} version is not the resource's. */
  public static Refusal preconditionFailed(String detail) {
    return new Refusal(RefusalKind.PRECONDITION_FAILED, "precondition_failed", detail, null);
  }

  /** 428 precondition_required: this change must name the version it was made from. */
  public static Refusal preconditionRequired(String detail) {
    return new Refusal(RefusalKind.PRECONDITION_REQUIRED, "precondition_required", detail, null);
  }

  public static Refusal malformed(String detail) {
    return new Refusal(RefusalKind.MALFORMED, "malformed_request", detail, null);
  }

  /** The same refusal about the given field. */
  public Refusal about(String fieldPath) {
    return new Refusal(kind, reason, getMessage(), fieldPath, retryAfterSeconds, extras);
  }

  public RefusalKind kind() {
    return kind;
  }

  public String reason() {
    return reason;
  }

  public String field() {
    return field;
  }
}
