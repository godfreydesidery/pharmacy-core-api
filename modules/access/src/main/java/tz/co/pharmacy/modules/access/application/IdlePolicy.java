package tz.co.pharmacy.modules.access.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How long a session may go unused before it ends. A pharmacy sets its own limit (5 to 60 minutes,
 * 15 by default); a session that has not chosen a pharmacy yet, and every session of a root
 * administrator, keeps the platform's limit. {@code idle-timeout-enabled: false} (the simulated
 * world, whose staff are scripts) opens sessions without one.
 */
@Component
class IdlePolicy {

  private final boolean enabled;
  private final int platformMinutes;

  IdlePolicy(
      @Value("${pharmacy.access.idle-timeout-enabled:true}") boolean enabled,
      @Value("${pharmacy.access.idle-minutes:15}") int platformMinutes) {
    this.enabled = enabled;
    this.platformMinutes = platformMinutes;
  }

  /** The limit of a new session, before a pharmacy is chosen; null without one. */
  Integer initial() {
    return enabled ? platformMinutes : null;
  }

  /** The limit while working in a pharmacy with {@code pharmacyMinutes} (null: none chosen). */
  Integer minutesFor(boolean rootAdmin, Integer pharmacyMinutes) {
    if (!enabled) {
      return null;
    }
    return rootAdmin || pharmacyMinutes == null ? platformMinutes : pharmacyMinutes;
  }
}
