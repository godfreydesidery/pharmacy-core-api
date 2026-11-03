package tz.co.pharmacy.delivery.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A sliding-window log per key, in memory: a request is allowed when fewer than {@code limit}
 * requests of the key fell in the last {@code window}. Exact at the window's edge, unlike a fixed
 * window, and small: one timestamp per allowed request, pruned as it ages out.
 */
@Component
public class SlidingWindowRateLimiter implements RateLimiter {

  private final Map<String, Deque<Instant>> windows = new ConcurrentHashMap<>();
  private volatile Duration longestWindow = Duration.ofMinutes(1);

  @Override
  public OptionalLong acquire(String key, int limit, Duration window, Instant at) {
    if (window.compareTo(longestWindow) > 0) {
      longestWindow = window;
    }
    Deque<Instant> log = windows.computeIfAbsent(key, ignored -> new ArrayDeque<>());
    synchronized (log) {
      Instant oldest = at.minus(window);
      while (!log.isEmpty() && !log.peekFirst().isAfter(oldest)) {
        log.pollFirst();
      }
      if (log.size() >= limit) {
        Duration wait = Duration.between(at, log.peekFirst().plus(window));
        return OptionalLong.of(Math.max(1, (wait.toMillis() + 999) / 1000));
      }
      log.addLast(at);
      return OptionalLong.empty();
    }
  }

  /** Drops keys whose every request aged out. */
  @Scheduled(fixedDelay = 300_000)
  void prune() {
    Instant oldest = Instant.now().minus(longestWindow);
    windows
        .entrySet()
        .removeIf(
            entry -> {
              synchronized (entry.getValue()) {
                return entry.getValue().isEmpty() || !entry.getValue().peekLast().isAfter(oldest);
              }
            });
  }
}
