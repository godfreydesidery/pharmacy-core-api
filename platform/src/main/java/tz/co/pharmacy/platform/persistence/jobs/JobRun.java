package tz.co.pharmacy.platform.persistence.jobs;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One run of a scheduled job, handed to the job while it runs: the job notes what it did and, for
 * each pharmacy (or part of the work) that failed, the error, instead of dropping it. A run with a
 * failure is recorded FAILED in {@code platform.job_runs}, logged at ERROR and counted. Safe to use
 * from the job's own worker threads.
 */
public final class JobRun {

  /** The most failures one run records; the rest are counted. */
  static final int MAX_FAILURES = 200;

  private static final int MAX_TEXT = 1_000;

  /** A part of the run that failed: a pharmacy ({@code tenantUid}), or the platform (null). */
  public record Failure(UUID tenantUid, String error) {}

  private final String job;
  private final List<Failure> failures = new ArrayList<>();
  private int failureCount;
  private volatile String message;

  public JobRun(String job) {
    this.job = job;
  }

  public String job() {
    return job;
  }

  /** Records that the work for {@code tenantUid} (null: not a pharmacy's) failed. */
  public synchronized void failed(UUID tenantUid, String error) {
    failureCount++;
    if (failures.size() < MAX_FAILURES) {
      failures.add(new Failure(tenantUid, trim(error == null ? "failed" : error)));
    }
  }

  /** The same, with the exception that stopped it. */
  public void failed(UUID tenantUid, Throwable failure) {
    failed(tenantUid, failure == null ? null : failure.toString());
  }

  /** What the run did, in a few words (kept with the run). */
  public void note(String what) {
    this.message = what == null ? null : trim(what);
  }

  public String message() {
    return message;
  }

  public synchronized List<Failure> failures() {
    return List.copyOf(failures);
  }

  /** Every failure recorded, also those beyond {@link #MAX_FAILURES}. */
  public synchronized int failureCount() {
    return failureCount;
  }

  public synchronized boolean hasFailures() {
    return failureCount > 0;
  }

  static String trim(String text) {
    return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) + "…" : text;
  }
}
