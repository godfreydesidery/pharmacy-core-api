package tz.co.pharmacy.platform.composition;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.crypto.KeyRotation;
import tz.co.pharmacy.platform.crypto.Keyring;
import tz.co.pharmacy.platform.crypto.Purpose;
import tz.co.pharmacy.platform.crypto.SealedColumn;
import tz.co.pharmacy.platform.crypto.SealedValues;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Re-seals every sealed value that is not under its purpose's active key (OPS-5): every column the
 * modules declare ({@link SealedColumn}), at platform scope and then in every pharmacy (active,
 * suspended or closed alike), under each one's row-level security, in batches of short
 * transactions. One instance runs it at a time ({@link SingleRunner}); progress is logged per
 * column and pharmacy. The status counts the values per key id over every pharmacy, so an old key
 * leaves the keyring only once nothing names it.
 */
@Component
class KeyResealJob implements KeyRotation {

  private static final Logger log = LoggerFactory.getLogger(KeyResealJob.class);
  private static final String SERVICE = "key-reseal";

  private final Keyring keyring;
  private final SealedValues values;
  private final ObjectProvider<SealedColumn> columns;
  private final TenantService tenants;
  private final SingleRunner runner;
  private final Clock clock;
  private final int batch;
  private final AtomicBoolean running = new AtomicBoolean();
  private final AtomicReference<Run> lastRun = new AtomicReference<>();

  KeyResealJob(
      Keyring keyring,
      SealedValues values,
      ObjectProvider<SealedColumn> columns,
      TenantService tenants,
      SingleRunner runner,
      Clock clock,
      @Value("${pharmacy.sealing.reseal-batch:200}") int batch) {
    this.keyring = keyring;
    this.values = values;
    this.columns = columns;
    this.tenants = tenants;
    this.runner = runner;
    this.clock = clock;
    this.batch = batch;
  }

  @Override
  public boolean start() {
    if (running.get()) {
      return false;
    }
    CompletableFuture<Boolean> took = new CompletableFuture<>();
    Thread.ofVirtual()
        .name(SERVICE)
        .start(
            () -> {
              try {
                boolean ran =
                    runner.runAlone(
                        SERVICE,
                        () -> {
                          // Running before the caller hears so: its next status shows this run.
                          Instant started = clock.instant();
                          running.set(true);
                          lastRun.set(new Run(started, null, "running", 0, 0));
                          took.complete(true);
                          resealAll(started);
                        });
                took.complete(ran);
              } catch (RuntimeException failure) {
                took.completeExceptionally(failure);
                log.warn("Key re-seal could not start: {}", failure.getMessage());
              }
            });
    try {
      return took.get(30, TimeUnit.SECONDS);
    } catch (ExecutionException | TimeoutException failure) {
      throw new IllegalStateException("The key re-seal could not start", failure);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while starting the key re-seal", interrupted);
    }
  }

  private void resealAll(Instant started) {
    AtomicLong resealed = new AtomicLong();
    AtomicLong unreadable = new AtomicLong();
    log.info("Key re-seal started; active keys {}", keyring.activeKeys());
    try {
      List<UUID> pharmacies = allTenants();
      for (SealedColumn column : columns.orderedStream().toList()) {
        String key = keyring.activeKey(column.purpose());
        long before = resealed.get();
        resealScope(column, null, resealed, unreadable);
        if (column.tenantScoped()) {
          for (UUID tenant : pharmacies) {
            resealScope(column, tenant, resealed, unreadable);
          }
        }
        log.info(
            "Key re-seal: {} done, {} values moved to key '{}'",
            column.name(),
            resealed.get() - before,
            key);
        lastRun.set(new Run(started, null, "running", resealed.get(), unreadable.get()));
      }
      lastRun.set(new Run(started, clock.instant(), "completed", resealed.get(), unreadable.get()));
      log.info(
          "Key re-seal completed: {} values re-sealed, {} unreadable",
          resealed.get(),
          unreadable.get());
    } catch (RuntimeException failure) {
      lastRun.set(
          new Run(
              started,
              clock.instant(),
              "failed: " + failure.getMessage(),
              resealed.get(),
              unreadable.get()));
      log.warn("Key re-seal failed after {} values: {}", resealed.get(), failure.toString());
    } finally {
      running.set(false);
    }
  }

  private void resealScope(
      SealedColumn column, UUID tenant, AtomicLong resealed, AtomicLong unreadable) {
    long after = 0;
    long here = 0;
    while (true) {
      long from = after;
      SealedValues.Batch done = inScope(tenant, () -> values.reseal(column, from, batch));
      here += done.resealed();
      resealed.addAndGet(done.resealed());
      unreadable.addAndGet(done.unreadable());
      if (done.unreadable() > 0) {
        log.warn(
            "Key re-seal: {} values of {} ({}) open with no key in the keyring; left as they are",
            done.unreadable(),
            column.name(),
            tenant == null ? "platform" : "tenant " + tenant);
      }
      if (!done.more()) {
        break;
      }
      after = done.lastId();
      log.info(
          "Key re-seal: {} ({}) {} values so far",
          column.name(),
          tenant == null ? "platform" : "tenant " + tenant,
          here);
    }
  }

  @Override
  public Status status() {
    Map<String, String> active = keyring.activeKeys();
    List<UUID> pharmacies = allTenants();
    List<ColumnCounts> counts = new ArrayList<>();
    Map<String, Long> byKey = new TreeMap<>();
    long toReseal = 0;
    for (SealedColumn column : columns.orderedStream().toList()) {
      Map<String, Long> here = new TreeMap<>(inScope(null, () -> values.count(column)));
      if (column.tenantScoped()) {
        for (UUID tenant : pharmacies) {
          inScope(tenant, () -> values.count(column))
              .forEach((id, n) -> here.merge(id, n, Long::sum));
        }
      }
      counts.add(new ColumnCounts(column.name(), column.purpose().id(), here));
      String key = keyring.activeKey(column.purpose());
      for (Map.Entry<String, Long> entry : here.entrySet()) {
        byKey.merge(entry.getKey(), entry.getValue(), Long::sum);
        // Keyed hashes stay under their key (they cannot be re-sealed); counted, not to move.
        if (!column.keyedHash() && !entry.getKey().equals(key)) {
          toReseal += entry.getValue();
        }
      }
    }
    List<KeyUse> keys = new ArrayList<>();
    for (String id : new TreeSet<>(keyring.keyIds())) {
      List<String> activeFor = new ArrayList<>();
      for (Purpose purpose : Purpose.values()) {
        if (id.equals(keyring.activeKey(purpose))) {
          activeFor.add(purpose.id());
        }
      }
      long used = byKey.getOrDefault(id, 0L);
      keys.add(new KeyUse(id, activeFor, used, used == 0 && activeFor.isEmpty()));
    }
    Map<String, Long> unknown = new TreeMap<>(byKey);
    unknown.keySet().removeAll(keyring.keyIds());
    unknown.remove(SealedValues.PLAIN);
    unknown.remove(SealedValues.UNKEYED);
    return new Status(active, keys, counts, unknown, toReseal, running.get(), lastRun.get());
  }

  private <T> T inScope(UUID tenant, Supplier<T> work) {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(SERVICE, null, tenant),
        () -> TenantContext.callAs(tenant, work));
  }

  /** Every pharmacy, whatever its status: a suspended or closed one keeps its sealed values. */
  private List<UUID> allTenants() {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(SERVICE, null, null),
        () -> {
          List<UUID> all = new ArrayList<>();
          PageQuery query = PageQuery.first(100);
          while (true) {
            Page<TenantView> page = tenants.list(null, query);
            page.items().forEach(t -> all.add(t.uid()));
            if (page.nextCursor() == null) {
              return all;
            }
            query = new PageQuery(page.nextCursor(), 100);
          }
        });
  }
}
