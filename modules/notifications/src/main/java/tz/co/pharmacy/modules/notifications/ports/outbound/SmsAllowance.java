package tz.co.pharmacy.modules.notifications.ports.outbound;

import java.time.ZoneId;
import java.util.UUID;

/**
 * How many SMS a month a pharmacy's plan includes on the platform's account (its {@code
 * sms_per_month}), and the pharmacy's time zone, whose calendar month the count follows. A
 * pharmacy's own SMS provider is not capped.
 */
public interface SmsAllowance {

  Allowance of(UUID tenantUid);

  /** {@code perMonth} null is unlimited. */
  record Allowance(Integer perMonth, String planName, ZoneId zone) {}
}
