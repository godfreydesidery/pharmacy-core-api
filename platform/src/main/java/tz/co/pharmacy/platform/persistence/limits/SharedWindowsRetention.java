package tz.co.pharmacy.platform.persistence.limits;

import java.time.Instant;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.persistence.retention.RetentionTask;

/**
 * The nightly retention's part for {@link SharedWindows}: rows whose every hit and lock expired.
 */
@Component
@Order(100)
class SharedWindowsRetention implements RetentionTask {

  private static final int BATCH = 5_000;
  private static final int MAX_ROUNDS = 1_000;

  private final SharedWindows windows;

  SharedWindowsRetention(SharedWindows windows) {
    this.windows = windows;
  }

  @Override
  public Map<String, Long> run(Instant now) {
    long deleted = 0;
    for (int round = 0; round < MAX_ROUNDS; round++) {
      int n = windows.purgeExpired(now, BATCH);
      deleted += n;
      if (n < BATCH) {
        break;
      }
    }
    return Map.of("platform.rate_windows", deleted);
  }
}
