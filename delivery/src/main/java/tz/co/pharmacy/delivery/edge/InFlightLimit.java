package tz.co.pharmacy.delivery.edge;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The most API requests this instance works on at once ({@code pharmacy.edge.max-in-flight}, 100),
 * whoever sends them: past it a request waits up to {@code pharmacy.edge.max-in-flight-wait-ms}
 * (250 ms) for one to finish, then is refused at once with 503 {@code server_busy} and {@code
 * Retry-After} (adversarial review PERF-2). Shedding the excess early keeps the requests that are
 * let in fast, instead of letting every request queue for one of the {@code PHARMACY_DB_POOL_SIZE}
 * database connections until they all time out.
 *
 * <p>It runs before the {@link EdgeFilter}, so a refused request costs no database work, and it
 * sits beside the per-pharmacy fair share ({@link TenantConcurrency}, 10 at once, 429), which stops
 * one pharmacy taking this whole limit. Per instance, like the pool it protects. The live
 * notification stream, which stays open for minutes and holds no connection, and the health probes
 * (outside {@code /api}) are not counted.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class InFlightLimit extends OncePerRequestFilter {

  private static final Logger log = LoggerFactory.getLogger(InFlightLimit.class);

  private final Semaphore permits;
  private final int limit;
  private final long waitMillis;
  private final long retryAfterSeconds;
  private final Counter refused;

  public InFlightLimit(
      @Value("${pharmacy.edge.max-in-flight:100}") int limit,
      @Value("${pharmacy.edge.max-in-flight-wait-ms:250}") long waitMillis,
      @Value("${pharmacy.edge.max-in-flight-retry-after-seconds:2}") long retryAfterSeconds,
      ObjectProvider<MeterRegistry> meters) {
    this.limit = Math.max(1, limit);
    this.permits = new Semaphore(this.limit);
    this.waitMillis = Math.max(0, waitMillis);
    this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    MeterRegistry registry = meters.getIfAvailable();
    if (registry != null) {
      Gauge.builder("pharmacy.edge.in_flight", this, InFlightLimit::inFlight)
          .description("API requests in progress on this instance")
          .register(registry);
      this.refused =
          Counter.builder("pharmacy.edge.shed")
              .description("API requests refused 503 because the instance was at its limit")
              .register(registry);
    } else {
      this.refused = null;
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = EdgeFilter.path(request);
    return !path.startsWith("/api") || EdgeFilter.STREAM_PATH.equals(path);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (!acquire()) {
      if (refused != null) {
        refused.increment();
      }
      log.info("At {} API requests in progress; a request was refused 503", limit);
      String given = request.getHeader(EdgeFilter.CORRELATION_HEADER);
      String correlationId =
          given != null && given.matches("[A-Za-z0-9._-]{8,64}")
              ? given
              : UUID.randomUUID().toString();
      response.setHeader(EdgeFilter.CORRELATION_HEADER, correlationId);
      SecurityHeaders.apply(request, response);
      Problems.write(
          response,
          Refusal.unavailable(
              "server_busy",
              "The server is busy with other requests; retry in a moment.",
              retryAfterSeconds),
          correlationId);
      return;
    }
    try {
      chain.doFilter(request, response);
    } finally {
      permits.release();
    }
  }

  private boolean acquire() {
    try {
      return waitMillis == 0
          ? permits.tryAcquire()
          : permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  /** Requests in progress now (metrics, tests). */
  public int inFlight() {
    return limit - permits.availablePermits();
  }

  /** The limit (tests). */
  public int limit() {
    return limit;
  }
}
