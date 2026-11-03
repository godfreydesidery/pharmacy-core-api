package tz.co.pharmacy.modules.access.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts failures per key in a sliding window: {@code maxFailures} within {@code window} locks the
 * key for {@code lockFor}. In memory per node, for keys the database cannot hold, such as a client
 * IP or an email with no account (so probing unknown emails is throttled like known ones).
 */
final class FailureWindow {

  private final int maxFailures;
  private final Duration window;
  private final Duration lockFor;
  private final Map<String, Entry> entries = new ConcurrentHashMap<>();

  FailureWindow(int maxFailures, Duration window, Duration lockFor) {
    this.maxFailures = maxFailures;
    this.window = window;
    this.lockFor = lockFor;
  }

  /** How long {@code key} stays locked at {@code at}, or empty when it is not locked. */
  Optional<Duration> lockedFor(String key, Instant at) {
    Entry entry = entries.get(key);
    if (entry == null) {
      return Optional.empty();
    }
    synchronized (entry) {
      if (entry.lockedUntil != null && entry.lockedUntil.isAfter(at)) {
        return Optional.of(Duration.between(at, entry.lockedUntil));
      }
      return Optional.empty();
    }
  }

  /** Counts a failure; returns true when this failure locked the key. */
  boolean fail(String key, Instant at) {
    Entry entry = entries.computeIfAbsent(key, ignored -> new Entry());
    synchronized (entry) {
      Instant oldest = at.minus(window);
      while (!entry.failures.isEmpty() && !entry.failures.peekFirst().isAfter(oldest)) {
        entry.failures.pollFirst();
      }
      entry.failures.addLast(at);
      if (entry.failures.size() >= maxFailures) {
        entry.failures.clear();
        entry.lockedUntil = at.plus(lockFor);
        return true;
      }
      return false;
    }
  }

  void clear(String key) {
    entries.remove(key);
  }

  /** Drops entries with nothing left to remember, so the map stays small. */
  void prune(Instant at) {
    Instant oldest = at.minus(window);
    entries
        .entrySet()
        .removeIf(
            item -> {
              Entry entry = item.getValue();
              synchronized (entry) {
                boolean locked = entry.lockedUntil != null && entry.lockedUntil.isAfter(at);
                boolean recent =
                    !entry.failures.isEmpty() && entry.failures.peekLast().isAfter(oldest);
                return !locked && !recent;
              }
            });
  }

  private static final class Entry {
    private final Deque<Instant> failures = new ArrayDeque<>();
    private Instant lockedUntil;
  }
}
