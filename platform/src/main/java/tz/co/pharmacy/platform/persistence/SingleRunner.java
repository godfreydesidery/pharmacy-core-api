package tz.co.pharmacy.platform.persistence;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.persistence.jobs.JobRun;
import tz.co.pharmacy.platform.persistence.jobs.JobRuns;
import tz.co.pharmacy.platform.persistence.jobs.JobRuns.Outcome;

/**
 * Runs the scheduled jobs and records every run, on one instance at a time when several run side by
 * side (several ECS tasks).
 *
 * <ul>
 *   <li>{@link #runAlone} takes a PostgreSQL session advisory lock, {@code pg_try_advisory_lock},
 *       named after the job, on a connection it holds for the run. An instance that finds the lock
 *       taken skips this round (recorded SKIPPED); the lock goes with the connection, so a crashed
 *       instance never leaves it held.
 *   <li>{@link #runHere} runs a job on this instance without the lock, for work that claims its own
 *       rows ({@code FOR UPDATE SKIP LOCKED}), such as the export worker.
 * </ul>
 *
 * Each run is a row of {@code platform.job_runs} (job, instance, start, end, outcome, message and
 * the pharmacies it failed for) and is counted in the metrics {@code pharmacy.jobs.runs} (by job
 * and outcome), {@code pharmacy.jobs.duration} and {@code pharmacy.jobs.failures}. A run that
 * throws, or that records a failure, is FAILED and is logged at ERROR with the stable prefix
 * {@value #FAILED_PREFIX}, for log-based alarms (a CloudWatch metric filter). A job's exception
 * never escapes to the scheduler; a failure to record never stops the job.
 */
@Component
public class SingleRunner {

  /** The start of the ERROR line a failed run logs; alarms match on it. */
  public static final String FAILED_PREFIX = "JOB_FAILED";

  private static final Logger LOG = LoggerFactory.getLogger(SingleRunner.class);

  private final DataSource dataSource;
  private final JobRuns runs;
  private final InstanceName instance;
  private final MeterRegistry meters;

  /**
   * The statement timeout of the transactions a job begins ({@code
   * pharmacy.database.job-statement-timeout}, 5 minutes): a nightly purge or a run over every
   * pharmacy has no one waiting on it, and should not fail at the 30 seconds a request gets.
   */
  private final Duration jobStatementTimeout;

  public SingleRunner(
      DataSource dataSource, JobRuns runs, InstanceName instance, MeterRegistry meters) {
    this(dataSource, runs, instance, meters, DEFAULT_JOB_STATEMENT_TIMEOUT);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public SingleRunner(
      DataSource dataSource,
      JobRuns runs,
      InstanceName instance,
      MeterRegistry meters,
      @org.springframework.beans.factory.annotation.Value(
              "${pharmacy.database.job-statement-timeout:PT5M}")
          Duration jobStatementTimeout) {
    this.dataSource = dataSource;
    this.runs = runs;
    this.instance = instance;
    this.meters = meters;
    this.jobStatementTimeout = jobStatementTimeout;
  }

  static final Duration DEFAULT_JOB_STATEMENT_TIMEOUT = Duration.ofMinutes(5);

  /** Runs {@code job} unless another instance is running the job of this name; returns whether. */
  public boolean runAlone(String jobName, Runnable job) {
    return runAlone(jobName, run -> job.run());
  }

  /**
   * Runs {@code job}, recorded, unless another instance is running the job of this name (then it is
   * recorded SKIPPED); returns whether it ran here.
   */
  public boolean runAlone(String jobName, Consumer<JobRun> job) {
    Connection connection;
    try {
      connection = dataSource.getConnection();
    } catch (SQLException | RuntimeException failure) {
      // No database: nothing can run or be recorded; the next round tries again.
      count(jobName, Outcome.FAILED, Duration.ZERO, 1);
      LOG.error(
          "{} job={} instance={} no database connection: {}",
          FAILED_PREFIX,
          jobName,
          instance,
          failure.toString());
      return false;
    }
    try (connection) {
      if (!lock(connection, "pg_try_advisory_lock", jobName)) {
        LOG.debug("{} is running on another instance; skipped", jobName);
        count(jobName, Outcome.SKIPPED, Duration.ZERO, 0);
        quietly(() -> runs.skipped(jobName, instance.get(), "running on another instance"));
        return false;
      }
      try {
        runHere(jobName, job);
        return true;
      } finally {
        unlock(connection, jobName);
      }
    } catch (SQLException failure) {
      count(jobName, Outcome.FAILED, Duration.ZERO, 1);
      LOG.error(
          "{} job={} instance={} could not take the job's lock: {}",
          FAILED_PREFIX,
          jobName,
          instance,
          failure.toString());
      return false;
    }
  }

  /** Runs {@code job} on this instance (no lock), recorded; returns its outcome. */
  public Outcome runHere(String jobName, Consumer<JobRun> job) {
    JobRun run = new JobRun(jobName);
    long[] id = {0};
    quietly(() -> id[0] = runs.started(jobName, instance.get()));
    long start = System.nanoTime();
    Throwable thrown = null;
    try {
      StatementTimeout.runWith(jobStatementTimeout, () -> job.accept(run));
    } catch (RuntimeException | Error failure) {
      thrown = failure;
    }
    Duration took = Duration.ofNanos(System.nanoTime() - start);
    Outcome outcome = thrown != null || run.hasFailures() ? Outcome.FAILED : Outcome.OK;
    String message = message(run, thrown);
    if (id[0] > 0) {
      quietly(() -> runs.finished(id[0], outcome, message, run.failures()));
    }
    count(jobName, outcome, took, run.failureCount() + (thrown == null ? 0 : 1));
    if (outcome == Outcome.FAILED) {
      LOG.error(
          "{} job={} instance={} took={}ms failures={} message={}{}",
          FAILED_PREFIX,
          jobName,
          instance,
          took.toMillis(),
          run.failureCount(),
          message,
          failuresText(run.failures()),
          thrown);
    }
    if (thrown instanceof Error error) {
      throw error;
    }
    return outcome;
  }

  private static String message(JobRun run, Throwable thrown) {
    if (thrown == null) {
      return run.message();
    }
    String error = thrown.toString();
    return run.message() == null ? error : run.message() + "; stopped: " + error;
  }

  private static String failuresText(List<JobRun.Failure> failures) {
    if (failures.isEmpty()) {
      return "";
    }
    StringBuilder text = new StringBuilder(" failed for:");
    for (JobRun.Failure failure : failures.subList(0, Math.min(10, failures.size()))) {
      text.append(' ')
          .append(failure.tenantUid() == null ? "platform" : failure.tenantUid())
          .append(" (")
          .append(failure.error())
          .append(')');
    }
    return text.toString();
  }

  private void count(String job, Outcome outcome, Duration took, int failures) {
    try {
      Counter.builder("pharmacy.jobs.runs")
          .description("Scheduled job runs, by job and outcome (OK, FAILED, SKIPPED)")
          .tag("job", job)
          .tag("outcome", outcome.name())
          .register(meters)
          .increment();
      if (outcome != Outcome.SKIPPED) {
        Timer.builder("pharmacy.jobs.duration")
            .description("How long scheduled job runs take")
            .tag("job", job)
            .tag("outcome", outcome.name())
            .register(meters)
            .record(took);
      }
      if (failures > 0) {
        Counter.builder("pharmacy.jobs.failures")
            .description("Failures in scheduled job runs (a pharmacy's part, or the whole run)")
            .tag("job", job)
            .register(meters)
            .increment(failures);
      }
    } catch (RuntimeException ignored) {
      // Metrics are bookkeeping.
    }
  }

  private static void quietly(Runnable bookkeeping) {
    try {
      bookkeeping.run();
    } catch (RuntimeException failure) {
      LOG.warn("Could not record a job run: {}", failure.toString());
    }
  }

  /**
   * Releases the job's lock. Should that fail, the connection is closed for good rather than
   * returned to the pool still holding the lock (a session lock lives as long as its connection).
   */
  private static void unlock(Connection connection, String jobName) {
    try {
      lock(connection, "pg_advisory_unlock", jobName);
    } catch (SQLException | RuntimeException failure) {
      LOG.warn("Could not release the lock of {}; dropping its connection", jobName, failure);
      try {
        connection.abort(Runnable::run);
      } catch (SQLException | RuntimeException ignored) {
        // closing anyway
      }
    }
  }

  private static boolean lock(Connection connection, String function, String jobName)
      throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement("select " + function + "(hashtextextended(?, 0))")) {
      statement.setString(1, "job:" + jobName);
      try (ResultSet result = statement.executeQuery()) {
        return result.next() && result.getBoolean(1);
      }
    }
  }
}
