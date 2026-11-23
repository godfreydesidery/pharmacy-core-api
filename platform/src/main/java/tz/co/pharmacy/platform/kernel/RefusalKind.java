package tz.co.pharmacy.platform.kernel;

/** Why a request was refused, independent of transport. The delivery layer maps it to HTTP. */
public enum RefusalKind {
  /** 400: the body is not JSON or does not fit the route. */
  MALFORMED(400),
  /** 422: a rule refused a value. */
  INVALID(422),
  NOT_FOUND(404),
  CONFLICT(409),
  FORBIDDEN(403),
  UNAUTHENTICATED(401),
  /** 413: a file larger than the route takes. */
  PAYLOAD_TOO_LARGE(413),
  /** 415: a file of a type the route does not take. */
  UNSUPPORTED_MEDIA_TYPE(415),
  /** 423: the account is locked for a while, such as after repeated failed sign-ins. */
  LOCKED(423),
  /** 429: too many requests from this client; retry after the given seconds. */
  TOO_MANY_REQUESTS(429),
  /**
   * 503: this instance is at its limit of requests in progress, or the database is; retry after the
   * given seconds (load shedding, adversarial review PERF-2).
   */
  SERVICE_UNAVAILABLE(503);

  private final int status;

  RefusalKind(int status) {
    this.status = status;
  }

  public int status() {
    return status;
  }
}
