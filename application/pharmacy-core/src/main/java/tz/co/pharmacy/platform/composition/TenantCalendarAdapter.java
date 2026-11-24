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
import tz.co.pharmacy.platform.kernel.PhoneNumbers;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.kernel.TenantLocale;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The current tenant's calendar from its time zone in tenancy, and its country (for local phone
 * numbers). Both are kept for a few minutes, so a sale does not read the tenant row for every
 * "today", and an edit applies soon after.
 */
@Component
class TenantCalendarAdapter implements TenantCalendar, TenantLocale {

  private static final Duration KEEP = Duration.ofMinutes(5);

  private record Cached(ZoneId zone, String country, Instant loadedAt) {}

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
    Cached cached = cached();
    return cached == null ? ZoneOffset.UTC : cached.zone();
  }

  @Override
  public String country() {
    Cached cached = cached();
    return cached == null ? PhoneNumbers.DEFAULT_COUNTRY : cached.country();
  }

  private Cached cached() {
    UUID tenant = TenantContext.tenantUid().orElse(null);
    if (tenant == null) {
      return null;
    }
    Instant now = clock.instant();
    Cached cached = zones.get(tenant);
    if (cached == null || cached.loadedAt().plus(KEEP).isBefore(now)) {
      cached = load(tenant, now);
      zones.put(tenant, cached);
    }
    return cached;
  }

  @Override
  public LocalDate today() {
    return LocalDate.ofInstant(clock.instant(), zone());
  }

  private Cached load(UUID tenant, Instant now) {
    TenantView view = tenants.getObject().find(tenant).orElse(null);
    ZoneId zone =
        view == null || view.timezone() == null || view.timezone().isBlank()
            ? ZoneOffset.UTC
            : ZoneId.of(view.timezone());
    String country =
        view == null || view.country() == null || view.country().isBlank()
            ? PhoneNumbers.DEFAULT_COUNTRY
            : view.country();
    return new Cached(zone, country, now);
  }
}
