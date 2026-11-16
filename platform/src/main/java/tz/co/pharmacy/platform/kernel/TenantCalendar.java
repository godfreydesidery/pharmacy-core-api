package tz.co.pharmacy.platform.kernel;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The current tenant's calendar. The server runs in UTC, but a pharmacy's "today" (the day a sale,
 * a payment or an expiry falls on) is its own local date: between midnight and 03:00 in Dar es
 * Salaam the UTC date is still yesterday. Outside a tenant (platform jobs) it is UTC.
 */
public interface TenantCalendar {

  /** The current tenant's time zone, or UTC outside a tenant. */
  ZoneId zone();

  /** Today in the current tenant's time zone. */
  LocalDate today();
}
