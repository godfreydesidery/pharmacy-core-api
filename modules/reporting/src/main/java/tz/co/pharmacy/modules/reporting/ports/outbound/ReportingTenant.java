package tz.co.pharmacy.modules.reporting.ports.outbound;

import java.time.ZoneId;

/** The current tenant's timezone, currency and expiry alert window. */
public interface ReportingTenant {

  ZoneId zone();

  String currency();

  int expiryAlertDays();
}
