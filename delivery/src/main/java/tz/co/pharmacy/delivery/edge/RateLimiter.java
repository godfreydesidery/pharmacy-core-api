package tz.co.pharmacy.delivery.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

/**
 * Limits how often a key (such as a client IP on the sign-in routes) may act, across every instance
 * of the API ({@link SharedRateLimiter}, in PostgreSQL).
 */
public interface RateLimiter {

  /**
   * Records one request for {@code key}; empty when it is allowed, otherwise the seconds until the
   * next one would be.
   */
  OptionalLong acquire(String key, int limit, Duration window, Instant at);
}
