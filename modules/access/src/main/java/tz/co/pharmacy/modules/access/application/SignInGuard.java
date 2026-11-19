package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Brute-force protection for every way of proving a password. An account locks after {@code
 * max-failures} failures within {@code window-minutes}, for {@code lock-minutes}; the lock is kept
 * on the user row, so it holds on every node. Failures for emails with no account, and per client
 * IP, are counted in memory, so probing unknown emails or spraying many accounts from one client is
 * throttled too. The IP limit is higher, because a pharmacy's tills share one address.
 */
@Component
class SignInGuard {

  private final int maxFailures;
  private final Duration window;
  private final Duration lockFor;
  private final FailureWindow unknownEmails;
  private final FailureWindow clients;

  private final Clock clock;

  SignInGuard(
      @Value("${pharmacy.access.lockout.max-failures:5}") int maxFailures,
      @Value("${pharmacy.access.lockout.window-minutes:15}") long windowMinutes,
      @Value("${pharmacy.access.lockout.lock-minutes:15}") long lockMinutes,
      @Value("${pharmacy.access.lockout.ip-max-failures:20}") int ipMaxFailures,
      Clock clock) {
    this.clock = clock;
    this.maxFailures = maxFailures;
    this.window = Duration.ofMinutes(windowMinutes);
    this.lockFor = Duration.ofMinutes(lockMinutes);
    this.unknownEmails = new FailureWindow(maxFailures, window, lockFor);
    this.clients = new FailureWindow(ipMaxFailures, window, lockFor);
  }

  /** 423 client_locked when this client failed too often. */
  void checkClient(String ip, Instant at) {
    if (ip == null) {
      return;
    }
    clients
        .lockedFor("ip:" + ip, at)
        .ifPresent(
            left -> {
              throw Refusal.locked(
                  "client_locked",
                  "Too many failed sign-ins from this device; try again later.",
                  left.toSeconds());
            });
  }

  /** 423 account_locked when the account, or the unknown email, is locked. */
  void checkAccount(User user, String email, Instant at) {
    if (user != null && user.lockedAt(at)) {
      throw locked(Duration.between(at, user.getLockedUntil()));
    }
    if (user == null) {
      unknownEmails
          .lockedFor("email:" + email, at)
          .ifPresent(
              left -> {
                throw locked(left);
              });
    }
  }

  /**
   * Counts a failure. For a known user it updates the row (call it in a transaction); returns true
   * when this failure locked the account.
   */
  boolean failed(User user, String email, String ip, Instant at) {
    if (ip != null) {
      clients.fail("ip:" + ip, at);
    }
    if (user == null) {
      return unknownEmails.fail("email:" + email, at);
    }
    return user.failedSignIn(at, maxFailures, window, lockFor);
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

  @Scheduled(fixedDelay = 600_000)
  void prune() {
    Instant now = clock.instant();
    unknownEmails.prune(now);
    clients.prune(now);
  }
}
