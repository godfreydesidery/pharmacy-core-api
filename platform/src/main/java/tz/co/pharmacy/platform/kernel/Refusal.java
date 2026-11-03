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

  public Refusal(RefusalKind kind, String reason, String detail, String field) {
    this(kind, reason, detail, field, null);
  }

  private Refusal(
      RefusalKind kind, String reason, String detail, String field, Long retryAfterSeconds) {
    super(detail);
    this.kind = kind;
    this.reason = reason;
    this.field = field;
    this.retryAfterSeconds = retryAfterSeconds;
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

  public static Refusal malformed(String detail) {
    return new Refusal(RefusalKind.MALFORMED, "malformed_request", detail, null);
  }

  /** The same refusal about the given field. */
  public Refusal about(String fieldPath) {
    return new Refusal(kind, reason, getMessage(), fieldPath);
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
