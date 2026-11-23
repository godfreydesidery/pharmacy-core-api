package tz.co.pharmacy.modules.access.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.limits.SharedWindows;

/**
 * Brute-force protection for every way of proving a password. An account locks after {@code
 * max-failures} failures within {@code window-minutes}, for {@code lock-minutes}; the lock is kept
 * on the user row. Failures for emails with no account, and per client IP, are counted in the
 * database too ({@link SharedWindows}, a sliding window per key), so probing unknown emails or
 * spraying many accounts from one client is throttled the same on every instance of the API. The IP
 * limit is higher, because a pharmacy's tills share one address.
 *
 * <p>Counted in the metrics {@code pharmacy.signin.failures} (by kind: account, unknown_email) and
 * {@code pharmacy.signin.locks} (account, unknown_email, client).
 */
@Component
class SignInGuard {

  private static final String CLIENTS = "signin-ip";
  private static final String UNKNOWN_EMAILS = "signin-email";

  private final int maxFailures;
  private final int ipMaxFailures;
  private final Duration window;
  private final Duration lockFor;
  private final SharedWindows windows;
  private final MeterRegistry meters;

  SignInGuard(
      @Value("${pharmacy.access.lockout.max-failures:5}") int maxFailures,
      @Value("${pharmacy.access.lockout.window-minutes:15}") long windowMinutes,
      @Value("${pharmacy.access.lockout.lock-minutes:15}") long lockMinutes,
      @Value("${pharmacy.access.lockout.ip-max-failures:20}") int ipMaxFailures,
      SharedWindows windows,
      MeterRegistry meters) {
    this.maxFailures = maxFailures;
    this.ipMaxFailures = ipMaxFailures;
    this.window = Duration.ofMinutes(windowMinutes);
    this.lockFor = Duration.ofMinutes(lockMinutes);
    this.windows = windows;
    this.meters = meters;
  }

  /** 423 client_locked when this client failed too often. */
  void checkClient(String ip, Instant at) {
    if (ip == null) {
      return;
    }
    windows
        .lockedFor(CLIENTS, ip, at)
        .ifPresent(
            left -> {
              throw Refusal.locked(
                  "client_locked",
                  "Too many failed sign-ins from this device; try again later.",
                  Math.max(1, left.toSeconds()));
            });
  }

  /** 423 account_locked when the account, or the unknown email, is locked. */
  void checkAccount(User user, String email, Instant at) {
    if (user != null && user.lockedAt(at)) {
      throw locked(Duration.between(at, user.getLockedUntil()));
    }
    if (user == null) {
      windows
          .lockedFor(UNKNOWN_EMAILS, email, at)
          .ifPresent(
              left -> {
                throw locked(left);
              });
    }
  }

  /**
   * Counts a failure: the client's, then the unknown email's or the account's. For a known user it
   * updates the row (call it in a transaction, or count the client first with {@link #clientFailed}
   * outside it and the account with {@link #accountFailed} inside); returns true when this failure
   * locked the account.
   */
  boolean failed(User user, String email, String ip, Instant at) {
    clientFailed(ip, at);
    if (user == null) {
      count("pharmacy.signin.failures", "unknown_email");
      boolean locked = windows.fail(UNKNOWN_EMAILS, email, maxFailures, window, lockFor, at);
      if (locked) {
        count("pharmacy.signin.locks", "unknown_email");
      }
      return locked;
    }
    return accountFailed(user, at);
  }

  /** Counts a failure from client {@code ip} (its own connection, committed at once). */
  void clientFailed(String ip, Instant at) {
    if (ip != null && windows.fail(CLIENTS, ip, ipMaxFailures, window, lockFor, at)) {
      count("pharmacy.signin.locks", "client");
    }
  }

  /** Counts a failure of a known account on its row; true when it locked the account. */
  boolean accountFailed(User user, Instant at) {
    count("pharmacy.signin.failures", "account");
    boolean locked = user.failedSignIn(at, maxFailures, window, lockFor);
    if (locked) {
      count("pharmacy.signin.locks", "account");
    }
    return locked;
  }

  Refusal locked(Duration left) {
    return Refusal.locked(
        "account_locked",
        "Too many failed sign-ins; the account is locked for a while.",
        Math.max(1, left.toSeconds()));
  }

  Duration lockFor() {
    return lockFor;
  }

  private void count(String name, String kind) {
    try {
      Counter.builder(name)
          .description(
              name.endsWith("locks")
                  ? "Sign-in locks set (an account, an unknown email or a client)"
                  : "Failed sign-ins (a known account or an unknown email)")
          .tag("kind", kind)
          .register(meters)
          .increment();
    } catch (RuntimeException ignored) {
      // Metrics are bookkeeping.
    }
  }
}
