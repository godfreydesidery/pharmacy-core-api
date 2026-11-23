package tz.co.pharmacy.platform.persistence.retention;

import java.time.Instant;
import java.util.Map;

/**
 * A part of the nightly retention that the platform cannot do itself, because it needs a module
 * (such as deleting expired export files from the store). Bound in composition; {@link Retention}
 * runs every task after its own purge, in the same single run across instances.
 */
public interface RetentionTask {

  /** Does its part as of {@code now}; returns what it removed, by name. */
  Map<String, Long> run(Instant now);
}
