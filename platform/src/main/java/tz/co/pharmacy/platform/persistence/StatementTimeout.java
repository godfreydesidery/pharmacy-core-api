package tz.co.pharmacy.platform.persistence;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * A longer {@code statement_timeout} for the transactions a piece of work begins on this thread,
 * such as a scheduled job ({@link SingleRunner}): {@link TenantAwareTransactionManager} sets it
 * with {@code SET LOCAL} at the start of each, in the same statement as the tenant, so it costs no
 * round trip and ends with the transaction. Outside such work, connections keep the pool's limit
 * ({@link DatabaseTimeouts}).
 */
public final class StatementTimeout {

  private static final ThreadLocal<Duration> FOR_THREAD = new ThreadLocal<>();

  private StatementTimeout() {}

  /** Runs {@code work} with {@code timeout} for every transaction it begins on this thread. */
  public static <T> T callWith(Duration timeout, Supplier<T> work) {
    Duration previous = FOR_THREAD.get();
    if (timeout == null || timeout.isNegative()) {
      FOR_THREAD.remove();
    } else {
      FOR_THREAD.set(timeout);
    }
    try {
      return work.get();
    } finally {
      if (previous == null) {
        FOR_THREAD.remove();
      } else {
        FOR_THREAD.set(previous);
      }
    }
  }

  public static void runWith(Duration timeout, Runnable work) {
    callWith(
        timeout,
        () -> {
          work.run();
          return null;
        });
  }

  /** The timeout this thread's work asked for, or null. */
  static Duration current() {
    return FOR_THREAD.get();
  }

  /** As PostgreSQL reads it: whole milliseconds; 0 turns the limit off. */
  static String setting(Duration timeout) {
    return Math.max(0, timeout.toMillis()) + "ms";
  }
}
