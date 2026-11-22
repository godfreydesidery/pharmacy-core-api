package tz.co.pharmacy.delivery.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;

/**
 * The pharmacy rules the edge enforces on every request (whether it requires a second factor), read
 * from its settings and kept for {@link #FRESH} so a request does not read them each time. A
 * pharmacy's own save forgets them at once; another instance learns within {@link #FRESH}.
 */
@Component
public class TenantPolicies {

  static final Duration FRESH = Duration.ofSeconds(30);

  private record Entry(boolean requireTwoFactor, Instant until) {}

  private final Map<UUID, Entry> known = new ConcurrentHashMap<>();
  private final SettingsService settings;

  public TenantPolicies(SettingsService settings) {
    this.settings = settings;
  }

  /** Whether the pharmacy requires a second factor; read in that pharmacy's request. */
  public boolean requiresTwoFactor(UUID tenantUid) {
    // Wall time: a cache's freshness, not business time.
    Instant now = Instant.now();
    Entry entry = known.get(tenantUid);
    if (entry == null || entry.until().isBefore(now)) {
      entry = new Entry(settings.current().requireTwoFactor(), now.plus(FRESH));
      known.put(tenantUid, entry);
    }
    return entry.requireTwoFactor();
  }

  /** The pharmacy changed its settings: read them again on its next request. */
  public void forget(UUID tenantUid) {
    known.remove(tenantUid);
  }
}
