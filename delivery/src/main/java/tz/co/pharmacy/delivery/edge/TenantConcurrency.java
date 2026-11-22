package tz.co.pharmacy.delivery.edge;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Fair share between pharmacies: each may have at most {@code pharmacy.edge.tenant-concurrency}
 * requests in progress at once (10 by default, half the database pool), so one pharmacy flooding
 * the platform cannot take every database connection from the others. A request over the limit
 * waits up to {@code pharmacy.edge.tenant-wait-ms} for a slot, then is refused 429 with a retry
 * time. The chaos run's flood (24 tills selling flat out) made a neighbour six times slower before
 * this.
 */
@Component
public class TenantConcurrency {

  private static final Logger log = LoggerFactory.getLogger(TenantConcurrency.class);

  private final Map<UUID, Semaphore> slots = new ConcurrentHashMap<>();
  private final int limit;
  private final long waitMillis;

  public TenantConcurrency(
      @Value("${pharmacy.edge.tenant-concurrency:10}") int limit,
      @Value("${pharmacy.edge.tenant-wait-ms:2000}") long waitMillis) {
    this.limit = Math.max(1, limit);
    this.waitMillis = Math.max(0, waitMillis);
  }

  /** A slot for one request of the pharmacy; close it when the request ends. */
  public Slot acquire(UUID tenantUid) {
    Semaphore semaphore = slots.computeIfAbsent(tenantUid, ignored -> new Semaphore(limit, true));
    boolean got;
    try {
      got = semaphore.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      got = false;
    }
    if (!got) {
      log.info(
          "Pharmacy {} is over its {} requests at once; a request was refused", tenantUid, limit);
      throw Refusal.tooManyRequests(
          "This pharmacy is sending more requests at once than the platform serves; retry in a"
              + " moment.",
          1);
    }
    return semaphore::release;
  }

  /** In use now by the pharmacy (for tests and diagnostics). */
  int inUse(UUID tenantUid) {
    Semaphore semaphore = slots.get(tenantUid);
    return semaphore == null ? 0 : limit - semaphore.availablePermits();
  }

  /** One request's hold on its pharmacy's share. */
  @FunctionalInterface
  public interface Slot extends AutoCloseable {
    @Override
    void close();
  }
}
