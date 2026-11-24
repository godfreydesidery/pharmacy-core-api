package tz.co.pharmacy.platform.kernel;

import java.util.Optional;
import java.util.UUID;

/**
 * Where the current request comes from, for the audit trail (STD-22, ISO 27001 A.8.15): the
 * client's IP address (as the edge reads it behind its proxies), its user agent (at most 300
 * characters) and the sign-in session it runs in. Set by the edge for each {@code /api} request and
 * cleared after it; a job or an after-commit reaction has none.
 */
public record RequestOrigin(String ip, String userAgent, UUID sessionUid) {

  private static final int USER_AGENT_LENGTH = 300;
  private static final ThreadLocal<RequestOrigin> CURRENT = new ThreadLocal<>();

  public RequestOrigin {
    if (userAgent != null && userAgent.length() > USER_AGENT_LENGTH) {
      userAgent = userAgent.substring(0, USER_AGENT_LENGTH);
    }
  }

  public static Optional<RequestOrigin> current() {
    return Optional.ofNullable(CURRENT.get());
  }

  public static void enter(RequestOrigin origin) {
    CURRENT.set(origin);
  }

  /** The same origin, in the given session (known once the bearer token is checked). */
  public RequestOrigin inSession(UUID session) {
    return new RequestOrigin(ip, userAgent, session);
  }

  public static void leave() {
    CURRENT.remove();
  }
}
