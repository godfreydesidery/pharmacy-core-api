package tz.co.pharmacy.platform.composition;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.files.ports.inbound.Archives;
import tz.co.pharmacy.modules.files.ports.inbound.FileView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.exports.DataExportJobs;
import tz.co.pharmacy.platform.persistence.jobs.JobRun;

/**
 * Builds the pharmacies' full exports in the background ({@code POST /data-export}). Every instance
 * polls; each job is claimed by one of them only ({@code FOR UPDATE SKIP LOCKED}), built into a
 * temporary file in short read transactions, stored in the file store as an archive (S3-compatible
 * in production) and marked READY, with heartbeats on the way. A job whose instance dies is claimed
 * again once its heartbeat is {@code pharmacy.exports.stale-after} old, up to {@code
 * pharmacy.exports.max-attempts} times.
 *
 * <p>The poll runs on every instance on purpose (no advisory lock): the claim is what makes a job
 * one instance's, and several instances share a busy night's exports. Each build is recorded as a
 * run of the job {@value #SERVICE} ({@link SingleRunner#runHere}) and counted in {@code
 * pharmacy.exports} by outcome (ready, retried, failed, abandoned).
 */
@Component
class DataExportWorker {

  private static final Logger LOG = LoggerFactory.getLogger(DataExportWorker.class);
  private static final String SERVICE = "data-export";
  private static final String ZIP = "application/zip";

  private final DataExportJobs jobs;
  private final TenantDataService data;
  private final Archives archives;
  private final Clock clock;
  private final Duration keep;
  private final Duration staleAfter;
  private final int maxAttempts;
  private final SingleRunner runner;
  private final MeterRegistry meters;

  @SuppressWarnings("java:S107")
  DataExportWorker(
      DataExportJobs jobs,
      TenantDataService data,
      Archives archives,
      Clock clock,
      SingleRunner runner,
      MeterRegistry meters,
      @Value("${pharmacy.exports.keep:P7D}") Duration keep,
      @Value("${pharmacy.exports.stale-after:PT10M}") Duration staleAfter,
      @Value("${pharmacy.exports.max-attempts:3}") int maxAttempts) {
    this.runner = runner;
    this.meters = meters;
    this.jobs = jobs;
    this.data = data;
    this.archives = archives;
    this.clock = clock;
    this.keep = keep;
    this.staleAfter = staleAfter;
    this.maxAttempts = Math.max(1, maxAttempts);
  }

  @Scheduled(
      fixedDelayString = "${pharmacy.exports.poll-every-ms:5000}",
      initialDelayString = "${pharmacy.exports.poll-every-ms:5000}")
  void poll() {
    try {
      // A few jobs per round at most, so one instance does not take every job of a busy night.
      for (int i = 0; i < 5 && runOne(); i++) {
        // next
      }
    } catch (RuntimeException failure) {
      LOG.warn("Export worker round failed: {}", failure.toString());
    }
  }

  /** Builds the next job, if there is one; returns whether there was. */
  boolean runOne() {
    Optional<DataExportJobs.Claim> claimed = jobs.claim(staleAfter, maxAttempts);
    claimed.ifPresent(
        claim ->
            runner.runHere(
                SERVICE,
                run ->
                    ExecutionContext.callAs(
                        ExecutionContext.Frame.service(SERVICE, null, claim.tenantUid()),
                        () -> TenantContext.callAs(claim.tenantUid(), () -> build(claim, run)))));
    return claimed.isPresent();
  }

  private Void build(DataExportJobs.Claim claim, JobRun run) {
    Path temp = null;
    try {
      TenantView tenant = data.exportable(claim.tenantUid());
      temp = Files.createTempFile("pharmacy-export-", ".zip");
      TenantDataService.Written written;
      try (OutputStream out = Files.newOutputStream(temp)) {
        written = data.write(tenant, out, () -> jobs.heartbeat(claim.tenantUid(), claim.uid()));
      }
      data.exportable(claim.tenantUid()); // not erased meanwhile
      long size = Files.size(temp);
      FileView file = archives.store(fileName(tenant), ZIP, claim.uid(), temp, size);
      if (!jobs.ready(claim.tenantUid(), claim.uid(), file.uid(), size, keep)) {
        // Given up on (or erased) while this worker was at it: the file is nobody's.
        archives.delete(file.uid());
        counted("abandoned");
        run.note("export " + claim.uid() + " was given up on while it was built");
        return null;
      }
      data.audited(claim.tenantUid(), claim.uid(), written);
      LOG.info("Export {} of {} is ready ({} bytes)", claim.uid(), tenant.code(), size);
      counted("ready");
      run.note("export " + claim.uid() + " ready (" + size + " bytes)");
    } catch (IOException | RuntimeException failure) {
      LOG.warn("Export {} failed (attempt {}): {}", claim.uid(), claim.attempts(), failure);
      String error = failure.toString();
      boolean last = claim.attempts() >= maxAttempts;
      if (last) {
        jobs.fail(claim.tenantUid(), claim.uid(), error);
      } else {
        jobs.retry(claim.tenantUid(), claim.uid(), error);
      }
      counted(last ? "failed" : "retried");
      run.failed(
          claim.tenantUid(),
          "export "
              + claim.uid()
              + " attempt "
              + claim.attempts()
              + (last ? " (gave up)" : "")
              + ": "
              + error);
    } finally {
      deleteQuietly(temp);
    }
    return null;
  }

  private void counted(String outcome) {
    try {
      Counter.builder("pharmacy.exports")
          .description("Full pharmacy exports built in the background, by outcome")
          .tag("outcome", outcome)
          .register(meters)
          .increment();
    } catch (RuntimeException ignored) {
      // Metrics are bookkeeping.
    }
  }

  private String fileName(TenantView tenant) {
    return tenant.code().replaceAll("[^A-Za-z0-9_-]", "_")
        + "-export-"
        + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
        + ".zip";
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      LOG.warn("could not delete the temporary export {}: {}", path, e.toString());
    }
  }
}
