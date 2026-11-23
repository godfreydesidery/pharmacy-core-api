package tz.co.pharmacy.modules.access.ports.outbound;

import java.util.UUID;

/**
 * The subscription plan's cap on a pharmacy's users. Access counts the seats itself, in the
 * transaction that takes one and under a lock, so parallel requests cannot pass the cap together;
 * composition binds this to the plan (tenancy).
 */
public interface UsersCap {

  /**
   * 422 {@code plan_limit_reached} when {@code inUse} seats plus {@code adding} more would go over
   * the plan's users cap; nothing when the plan has no cap.
   */
  void requireRoom(UUID tenantUid, long inUse, long adding);
}
