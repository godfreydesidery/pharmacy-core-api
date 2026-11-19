package tz.co.pharmacy.platform.kernel;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

/**
 * The application's clock, for code that cannot have the {@code Clock} bean injected: entities,
 * their mapped superclasses and static helpers. Spring installs the bean here at start, so this and
 * every injected clock tell the same time, whether that is the system clock or the simulated one.
 * Outside Spring (unit tests) it is the system clock in UTC.
 *
 * <p>Services take the {@code Clock} bean in their constructor instead; nothing reads {@code
 * Instant.now()} or {@code LocalDate.now()} on its own.
 */
public final class Clocks {

  private static volatile Clock clock = Clock.systemUTC();

  private Clocks() {}

  /** The clock in use. */
  public static Clock clock() {
    return clock;
  }

  /** The current instant on the clock in use. */
  public static Instant now() {
    return clock.instant();
  }

  /** Makes {@code replacement} the clock in use and returns it (the bean definition's value). */
  public static <C extends Clock> C install(C replacement) {
    clock = Objects.requireNonNull(replacement, "clock");
    return replacement;
  }
}
