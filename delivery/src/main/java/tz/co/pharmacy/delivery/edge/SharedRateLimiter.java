package tz.co.pharmacy.delivery.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.persistence.limits.SharedWindows;

/**
 * The edge's throttles, shared by every instance of the API: a sliding-window log per key in
 * PostgreSQL ({@link SharedWindows}). A request is allowed when fewer than {@code limit} requests
 * of the key fell in the last {@code window}; exact at the window's edge, as the in-memory log it
 * replaces was, and the same behind a load balancer that spreads one client over several tasks.
 */
@Component
public class SharedRateLimiter implements RateLimiter {

  private static final String KIND = "edge";

  private final SharedWindows windows;

  public SharedRateLimiter(SharedWindows windows) {
    this.windows = windows;
  }

  @Override
  public OptionalLong acquire(String key, int limit, Duration window, Instant at) {
    return windows.acquire(KIND, key, limit, window, at);
  }
}
