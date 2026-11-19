package tz.co.pharmacy.platform.kernel;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * The clock of a simulated world (the {@code sim} profile only): it runs at the speed of real time
 * from a moment the simulation chooses, and moves forward when told to, so a quarter of business
 * can be played in an afternoon while every rule that reads the time sees the simulated date.
 *
 * <p>It only moves forward: data written at a simulated moment must never appear to come from the
 * future. When a state file is given, every move is written to it and the clock resumes from it on
 * the next start, so restarting the server does not send the world back in time.
 */
public final class SimulatedClock extends Clock {

  private final Clock real;
  private final Path stateFile;
  private volatile Duration offset;

  /**
   * {@code real}: the clock that keeps running underneath (the system clock in production use).
   * {@code start}: where the world begins when there is no saved state, or null for now. {@code
   * stateFile}: where the position is kept across restarts, or null to keep it in memory only.
   */
  public SimulatedClock(Clock real, Instant start, Path stateFile) {
    this.real = real;
    this.stateFile = stateFile;
    Instant resumeAt = saved().orElse(start == null ? real.instant() : start);
    this.offset = Duration.between(real.instant(), resumeAt);
  }

  @Override
  public Instant instant() {
    return real.instant().plus(offset);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return ZoneOffset.UTC.equals(zone) ? this : new Zoned(this, zone);
  }

  /** Moves the clock forward by {@code step}. */
  public synchronized Instant advance(Duration step) {
    if (step == null || step.isNegative()) {
      throw Refusal.invalid(
          "clock_backwards", "The simulated clock only moves forward.", "duration");
    }
    offset = offset.plus(step);
    save();
    return instant();
  }

  /** Moves the clock forward to {@code target}. */
  public synchronized Instant moveTo(Instant target) {
    Instant now = instant();
    if (target == null || target.isBefore(now)) {
      throw Refusal.invalid(
          "clock_backwards", "The simulated clock is at " + now + " and only moves forward.", "to");
    }
    offset = Duration.between(real.instant(), target);
    save();
    return instant();
  }

  private Optional<Instant> saved() {
    if (stateFile == null || !Files.isRegularFile(stateFile)) {
      return Optional.empty();
    }
    try {
      String text = Files.readString(stateFile, StandardCharsets.UTF_8).trim();
      return text.isEmpty() ? Optional.empty() : Optional.of(Instant.parse(text));
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  private void save() {
    if (stateFile == null) {
      return;
    }
    try {
      Path parent = stateFile.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Files.writeString(stateFile, instant().toString(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /** The simulated time seen from another zone (tenant calendars ask for their own). */
  private static final class Zoned extends Clock {

    private final SimulatedClock base;
    private final ZoneId zone;

    Zoned(SimulatedClock base, ZoneId zone) {
      this.base = base;
      this.zone = zone;
    }

    @Override
    public Instant instant() {
      return base.instant();
    }

    @Override
    public ZoneId getZone() {
      return zone;
    }

    @Override
    public Clock withZone(ZoneId other) {
      return base.withZone(other);
    }
  }
}
