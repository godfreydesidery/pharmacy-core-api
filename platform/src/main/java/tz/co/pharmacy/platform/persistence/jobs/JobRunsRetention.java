package tz.co.pharmacy.platform.persistence.jobs;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.persistence.retention.RetentionTask;

/**
 * The nightly retention's part for {@link JobRuns}: runs older than {@code
 * pharmacy.retention.job-runs-days} (30) are deleted, counted on the database's wall time like the
 * runs themselves.
 */
@Component
@Order(100)
class JobRunsRetention implements RetentionTask {

  private final JobRuns runs;
  private final Duration keep;

  JobRunsRetention(JobRuns runs, @Value("${pharmacy.retention.job-runs-days:30}") int days) {
    this.runs = runs;
    this.keep = Duration.ofDays(Math.max(1, days));
  }

  @Override
  public Map<String, Long> run(Instant now) {
    return Map.of("platform.job_runs", (long) runs.trim(keep));
  }
}
