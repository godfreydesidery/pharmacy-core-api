package tz.co.pharmacy.platform.composition;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The current tenant's calendar from its time zone in tenancy. Zones are kept for a few minutes, so
 * a sale does not read the tenant row for every "today", and an edited zone applies soon after.
 */
@Component
class TenantCalendarAdapter implements TenantCalendar {

  private static final Duration KEEP = Duration.ofMinutes(5);

  private record Cached(ZoneId zone, Instant loadedAt) {}

  // Looked up on use: tenancy itself reaches the ledger, whose period lock needs this calendar.
  private final ObjectProvider<TenantService> tenants;
  private final Clock clock;
  private final Map<UUID, Cached> zones = new ConcurrentHashMap<>();

  TenantCalendarAdapter(ObjectProvider<TenantService> tenants, Clock clock) {
    this.tenants = tenants;
    this.clock = clock;
  }

  @Override
  public ZoneId zone() {
    UUID tenant = TenantContext.tenantUid().orElse(null);
    if (tenant == null) {
      return ZoneOffset.UTC;
    }
    Instant now = clock.instant();
    Cached cached = zones.get(tenant);
    if (cached == null || cached.loadedAt().plus(KEEP).isBefore(now)) {
      cached = new Cached(load(tenant), now);
      zones.put(tenant, cached);
    }
    return cached.zone();
  }

  @Override
  public LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), zone());
  }

  private ZoneId load(UUID tenant) {
    return tenants
        .getObject()
        .find(tenant)
        .map(TenantView::timezone)
        .filter(zone -> zone != null && !zone.isBlank())
        .map(ZoneId::of)
        .orElse(ZoneOffset.UTC);
  }
}
