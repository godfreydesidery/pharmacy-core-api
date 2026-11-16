package tz.co.pharmacy.modules.notifications.ports.inbound;

import java.time.YearMonth;
import java.util.Map;
import java.util.UUID;

/**
 * How many messages a tenant sent per channel, from the delivery log: for the pharmacy's usage view
 * and for subscription limits (such as SMS a month). Any caller may ask; the count runs in the
 * tenant's own scope.
 */
public interface NotificationUsage {

  /** Messages of the channel sent this calendar month (UTC). */
  long sentThisMonth(UUID tenantUid, Channel channel);

  /** Messages of the channel sent in the month. */
  long sentIn(UUID tenantUid, Channel channel, YearMonth month);

  /** Messages sent in the month, by channel (every channel present, zero when none). */
  Map<Channel, Long> usage(UUID tenantUid, YearMonth month);
}
