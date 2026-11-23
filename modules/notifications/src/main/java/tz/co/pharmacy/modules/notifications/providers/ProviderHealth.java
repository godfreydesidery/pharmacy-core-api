package tz.co.pharmacy.modules.notifications.providers;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * A circuit breaker per provider: after {@link #TRIP_AFTER} failures in a row a provider is
 * "tripped" for {@link #COOL_DOWN} and tried after the healthy ones, so one broken provider does
 * not slow every message; the first success closes it again. Kept in memory (each instance learns
 * for itself), which is enough: a tripped provider is still tried last, never skipped.
 *
 * <p>Per instance on purpose, also with several instances: it only orders the providers to try, it
 * never decides whether a message is sent. Each instance learns of a broken provider after {@link
 * #TRIP_AFTER} failures of its own (so with N instances at most N × 3 slow attempts before every
 * instance tries it last), and what root sees of a provider's health (last success, last error) is
 * on its row in the database, shared by all.
 */
@Component
public class ProviderHealth {

  static final int TRIP_AFTER = 3;
  static final Duration COOL_DOWN = Duration.ofMinutes(5);

  private record State(int failures, Instant trippedUntil) {}

  private final Map<UUID, State> states = new ConcurrentHashMap<>();
  private final Clock clock;

  public ProviderHealth(Clock clock) {
    this.clock = clock;
  }

  /** Whether the provider is in its cool-down after repeated failures. */
  public boolean tripped(UUID providerUid) {
    if (providerUid == null) {
      return false;
    }
    State state = states.get(providerUid);
    return state != null
        && state.trippedUntil() != null
        && state.trippedUntil().isAfter(clock.instant());
  }

  public void succeeded(UUID providerUid) {
    if (providerUid != null) {
      states.remove(providerUid);
    }
  }

  public void failed(UUID providerUid) {
    if (providerUid == null) {
      return;
    }
    states.compute(
        providerUid,
        (uid, state) -> {
          int failures = state == null ? 1 : state.failures() + 1;
          return new State(
              failures, failures >= TRIP_AFTER ? clock.instant().plus(COOL_DOWN) : null);
        });
  }
}
