package tz.co.pharmacy.platform.composition;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.files.ports.inbound.Archives;
import tz.co.pharmacy.modules.files.ports.inbound.FileDeletions;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.exports.DataExportJobs;
import tz.co.pharmacy.platform.persistence.jobs.JobRun;
import tz.co.pharmacy.platform.persistence.retention.RetentionTask;

/**
 * The nightly retention's part that needs the file store: the files of exports past their expiry
 * are deleted (the job is kept, EXPIRED, until the purge removes it), and file deletes that failed
 * before (an erasure's, an expired export's) are tried again.
 */
@Component
class DataLifecycleRetention implements RetentionTask {

  private static final Logger LOG = LoggerFactory.getLogger(DataLifecycleRetention.class);
  private static final String SERVICE = "retention";
  private static final int BATCH = 200;
  private static final int MAX_ROUNDS = 1_000;

  private final DataExportJobs jobs;
  private final Archives archives;
  private final FileDeletions deletions;

  DataLifecycleRetention(DataExportJobs jobs, Archives archives, FileDeletions deletions) {
    this.jobs = jobs;
    this.archives = archives;
    this.deletions = deletions;
  }

  @Override
  public Map<String, Long> run(Instant now) {
    return run(now, new JobRun(SERVICE));
  }

  @Override
  public Map<String, Long> run(Instant now, JobRun run) {
    long expired = 0;
    for (int round = 0; round < MAX_ROUNDS; round++) {
      List<DataExportJobs.Expiring> due = jobs.expiring(BATCH);
      if (due.isEmpty()) {
        break;
      }
      for (DataExportJobs.Expiring job : due) {
        try {
          ExecutionContext.callAs(
              ExecutionContext.Frame.service(SERVICE, null, job.tenantUid()),
              () ->
                  TenantContext.callAs(
                      job.tenantUid(),
                      () -> {
                        if (job.fileUid() != null) {
                          archives.delete(job.fileUid());
                        }
                        jobs.expired(job.tenantUid(), job.uid());
                        return null;
                      }));
          expired++;
        } catch (RuntimeException failure) {
          LOG.warn("Could not expire export {}: {}", job.uid(), failure.toString());
          run.failed(job.tenantUid(), "expiring export " + job.uid() + ": " + failure);
          return counts(expired, deletions.retryDue(BATCH * 5));
        }
      }
    }
    return counts(expired, deletions.retryDue(BATCH * 5));
  }

  private static Map<String, Long> counts(long expired, FileDeletions.RetryOutcome retried) {
    Map<String, Long> counts = new LinkedHashMap<>();
    counts.put("exports expired", expired);
    counts.put("file deletes retried", retried.deleted());
    counts.put("file deletes failing", retried.failed());
    return counts;
  }
}
