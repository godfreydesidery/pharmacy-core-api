package tz.co.pharmacy.modules.notifications.application;

import java.time.Duration;

/** When a failed message is tried again: 1, 5, 15 and 60 minutes after attempts 1 to 4. */
final class Backoff {

  private static final Duration[] STEPS = {
    Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(60)
  };

  private Backoff() {}

  /** The wait after {@code attempts} attempts have failed (1 or more). */
  static Duration after(int attempts) {
    int index = Math.max(0, Math.min(attempts, STEPS.length) - 1);
    return STEPS[index];
  }
}
