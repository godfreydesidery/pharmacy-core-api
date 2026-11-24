package tz.co.pharmacy.delivery.edge;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.inbound.MembershipGrant;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.persistence.signals.PostgresSignals;

/**
 * What the {@link EdgeFilter} read about a signed-in session's pharmacy and branch, kept for {@code
 * pharmacy.edge.access-cache-seconds} (30 s) on this instance (adversarial review PERF-9): the
 * pharmacy (its status), the person's membership (active, role permissions, branches) and the
 * branch (active). A request whose session, pharmacy and branch were read in the last 30 seconds
 * needs one database transaction before its handler (the session check) instead of four.
 *
 * <p><b>What is never cached:</b> the session itself. Every request still checks its token, expiry
 * and idle limit and that the person's account is active, so signing out, a revoked session, a
 * deactivated account and an idle sign-out take effect at the next request. Only successes are
 * kept: a refused request reads again next time.
 *
 * <p><b>Revocation.</b> The changes that must take effect at once evict on every instance: a
 * member's role, branches or deactivation ({@link #forgetTenant}), a role's permissions, a branch
 * closed, a pharmacy suspended, closed, reopened or erased (each the tenant), signing out and
 * leaving a pharmacy (the person, {@link #forgetUser}). The instance evicts at once, then signals
 * {@value #CHANNEL} through PostgreSQL {@code NOTIFY} ({@link PostgresSignals}); every instance,
 * itself included, evicts again when the signal arrives. When an instance's listener connection was
 * lost and is back it empties its cache, since signals sent meanwhile are lost.
 *
 * <p><b>The documented staleness.</b> What changes without one of those calls is seen within 30
 * seconds: a pharmacy suspended by the nightly subscription run (unpaid), a change made straight in
 * the database, and a signal lost while the database was unreachable.
 */
@Component
public class EdgeAccessCache {

  /** The PostgreSQL channel evictions are signalled on. */
  public static final String CHANNEL = "pharmacy_edge_access";

  private static final Logger log = LoggerFactory.getLogger(EdgeAccessCache.class);
  private static final String ALL = "*";

  /** One session acting in one pharmacy (and branch, or none). */
  public record Key(
      UUID sessionUid, UUID userUid, boolean rootAdmin, UUID tenantUid, UUID branchUid) {}

  /**
   * The pharmacy, the membership (null for a root administrator), the branch (null when the request
   * names none) and the pharmacy's inactivity limit in minutes (null when not read: a root
   * administrator, or a refused request). A change of the limit is seen within the cache's 30
   * seconds.
   */
  public record Access(
      TenantView tenant, MembershipGrant grant, BranchView branch, Integer idleMinutes) {

    public Access(TenantView tenant, MembershipGrant grant, BranchView branch) {
      this(tenant, grant, branch, null);
    }
  }

  private final Cache<Key, Access> cache;
  private final PostgresSignals signals;

  /**
   * Moves on at every eviction: an access read while one happened is not kept, as it may predate
   * the change.
   */
  private final java.util.concurrent.atomic.AtomicLong generation =
      new java.util.concurrent.atomic.AtomicLong();

  public EdgeAccessCache(
      PostgresSignals signals,
      @Value("${pharmacy.edge.access-cache-seconds:30}") long seconds,
      @Value("${pharmacy.edge.access-cache-size:20000}") long size) {
    this.signals = signals;
    this.cache =
        seconds <= 0
            ? null
            : Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(seconds))
                .maximumSize(Math.max(1, size))
                .build();
    signals.subscribe(
        CHANNEL,
        new PostgresSignals.Subscriber() {
          @Override
          public void received(String payload) {
            evict(payload);
          }

          @Override
          public void reconnected() {
            evict(ALL);
          }
        });
  }

  /**
   * The cached access of {@code key}, or {@code load}'s; kept only when {@code keep} says it is a
   * success.
   */
  public Access get(Key key, Supplier<Access> load, Predicate<Access> keep) {
    if (cache == null) {
      return load.get();
    }
    Access cached = cache.getIfPresent(key);
    if (cached != null) {
      return cached;
    }
    long before = generation.get();
    Access loaded = load.get();
    if (loaded != null && keep.test(loaded) && generation.get() == before) {
      cache.put(key, loaded);
    }
    return loaded;
  }

  /** Something about a pharmacy, its branches, roles or members changed: forget it everywhere. */
  public void forgetTenant(UUID tenantUid) {
    if (tenantUid != null) {
      forget("t:" + tenantUid);
    }
  }

  /** A person signed out, left a pharmacy or was changed: forget their sessions everywhere. */
  public void forgetUser(UUID userUid) {
    if (userUid != null) {
      forget("u:" + userUid);
    }
  }

  /** Something that may touch any pharmacy changed (the subscription run): forget everything. */
  public void forgetAll() {
    forget(ALL);
  }

  /** Entries held now (tests and diagnostics). */
  long size() {
    if (cache == null) {
      return 0;
    }
    cache.cleanUp();
    return cache.estimatedSize();
  }

  /** Whether an entry of the person is held (tests). */
  public boolean holdsUser(UUID userUid) {
    return cache != null
        && cache.asMap().keySet().stream().anyMatch(k -> k.userUid().equals(userUid));
  }

  /** Whether an entry of the pharmacy is held (tests). */
  public boolean holdsTenant(UUID tenantUid) {
    return cache != null
        && cache.asMap().keySet().stream().anyMatch(k -> k.tenantUid().equals(tenantUid));
  }

  private void forget(String payload) {
    evict(payload);
    try {
      signals.publish(CHANNEL, payload);
    } catch (RuntimeException failure) {
      // The other instances see the change within the cache's lifetime.
      log.warn("Could not signal an access change to the other instances: {}", failure.toString());
    }
  }

  /** "t:<tenant>", "u:<user>" or "*". */
  void evict(String payload) {
    if (cache == null || payload == null) {
      return;
    }
    generation.incrementAndGet();
    if (ALL.equals(payload)) {
      cache.invalidateAll();
      return;
    }
    UUID uid;
    try {
      uid = UUID.fromString(payload.substring(2));
    } catch (IllegalArgumentException | IndexOutOfBoundsException malformed) {
      return;
    }
    if (payload.startsWith("t:")) {
      cache.asMap().keySet().removeIf(key -> uid.equals(key.tenantUid()));
    } else if (payload.startsWith("u:")) {
      cache.asMap().keySet().removeIf(key -> uid.equals(key.userUid()));
    }
  }
}
