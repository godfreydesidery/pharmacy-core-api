package tz.co.pharmacy.delivery.edge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlidingWindowRateLimiterTest {

  private static final Instant T0 = Instant.parse("2026-11-03T08:00:00Z");
  private static final Duration MINUTE = Duration.ofMinutes(1);

  @Test
  @DisplayName("allows the limit within the window, then says how long to wait")
  void limitsWithinWindow() {
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
    for (int i = 0; i < 3; i++) {
      assertThat(limiter.acquire("ip:1", 3, MINUTE, T0.plusSeconds(i))).isEmpty();
    }
    assertThat(limiter.acquire("ip:1", 3, MINUTE, T0.plusSeconds(10))).hasValue(50);
    assertThat(limiter.acquire("ip:2", 3, MINUTE, T0.plusSeconds(10))).isEmpty();
  }

  @Test
  @DisplayName("the window slides: the oldest request ageing out frees one slot")
  void windowSlides() {
    SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
    limiter.acquire("k", 2, MINUTE, T0);
    limiter.acquire("k", 2, MINUTE, T0.plusSeconds(30));
    assertThat(limiter.acquire("k", 2, MINUTE, T0.plusSeconds(59))).isPresent();
    assertThat(limiter.acquire("k", 2, MINUTE, T0.plusSeconds(61))).isEmpty();
    assertThat(limiter.acquire("k", 2, MINUTE, T0.plusSeconds(62))).isPresent();
  }
}
