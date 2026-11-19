package tz.co.pharmacy.platform.kernel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SimulatedClockTest {

  static final Instant REAL = Instant.parse("2026-11-19T09:00:00Z");
  static final Instant START = Instant.parse("2026-12-01T05:00:00Z");

  /** A real clock that stands still, so the simulated time is exact. */
  static final Clock FROZEN = Clock.fixed(REAL, ZoneOffset.UTC);

  @Test
  @DisplayName("a new world begins at its start, or at real time when none is given")
  void begins() {
    assertThat(new SimulatedClock(FROZEN, START, null).instant()).isEqualTo(START);
    assertThat(new SimulatedClock(FROZEN, null, null).instant()).isEqualTo(REAL);
  }

  @Test
  @DisplayName("it moves forward by a duration or to a moment")
  void movesForward() {
    SimulatedClock clock = new SimulatedClock(FROZEN, START, null);

    assertThat(clock.advance(Duration.ofDays(1))).isEqualTo(START.plus(Duration.ofDays(1)));
    Instant target = Instant.parse("2027-03-01T05:00:00Z");
    assertThat(clock.moveTo(target)).isEqualTo(target);
    assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2027, 3, 1));
  }

  @Test
  @DisplayName("it never moves back")
  void neverBackwards() {
    SimulatedClock clock = new SimulatedClock(FROZEN, START, null);

    assertThatThrownBy(() -> clock.advance(Duration.ofHours(-1))).isInstanceOf(Refusal.class);
    assertThatThrownBy(() -> clock.moveTo(START.minusSeconds(1))).isInstanceOf(Refusal.class);
    assertThat(clock.instant()).isEqualTo(START);
  }

  @Test
  @DisplayName("it keeps running with real time between moves")
  void keepsRunning() {
    MovingClock real = new MovingClock(REAL);
    SimulatedClock running = new SimulatedClock(real, START, null);
    real.now = REAL.plusSeconds(90);
    assertThat(running.instant()).isEqualTo(START.plusSeconds(90));
  }

  @Test
  @DisplayName("a restart resumes where the world was, not at its start")
  void resumes(@TempDir Path dir) {
    Path state = dir.resolve("sim-clock.txt");
    Instant later = START.plus(Duration.ofDays(45));
    new SimulatedClock(FROZEN, START, state).moveTo(later);

    assertThat(new SimulatedClock(FROZEN, START, state).instant()).isEqualTo(later);
  }

  @Test
  @DisplayName("a tenant's zone sees the same moment, in its own calendar")
  void zones() {
    SimulatedClock clock = new SimulatedClock(FROZEN, Instant.parse("2026-12-31T22:30:00Z"), null);
    Clock dar = clock.withZone(ZoneId.of("Africa/Dar_es_Salaam"));

    assertThat(dar.instant()).isEqualTo(clock.instant());
    assertThat(LocalDate.now(dar)).isEqualTo(LocalDate.of(2027, 1, 1));
    assertThat(dar.withZone(ZoneOffset.UTC)).isSameAs(clock);
    clock.advance(Duration.ofHours(2));
    assertThat(dar.instant()).isEqualTo(clock.instant());
  }

  @Test
  @DisplayName("the installed clock is the one entities read")
  void installs() {
    Clock before = Clocks.clock();
    try {
      SimulatedClock clock = Clocks.install(new SimulatedClock(FROZEN, START, null));
      assertThat(Clocks.now()).isEqualTo(START);
      clock.advance(Duration.ofDays(2));
      assertThat(Clocks.now()).isEqualTo(START.plus(Duration.ofDays(2)));
    } finally {
      Clocks.install(before);
    }
  }

  static final class MovingClock extends Clock {
    Instant now;

    MovingClock(Instant now) {
      this.now = now;
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }
  }
}
