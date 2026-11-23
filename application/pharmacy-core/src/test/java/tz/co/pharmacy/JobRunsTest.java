package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.MeterRegistry;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Subscriptions;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.jobs.JobRuns;
import tz.co.pharmacy.platform.persistence.retention.Retention;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Job status (adversarial review OPS-7): every run of a scheduled job is a row of {@code
 * platform.job_runs} with its outcome and the pharmacies it failed for; a failed run logs an ERROR
 * line starting {@code JOB_FAILED} and is counted; root reads it all at {@code GET
 * /api/v1/platform/jobs}; metrics are published for Prometheus.
 */
class JobRunsTest extends IntegrationTest {

  @Autowired SingleRunner runner;
  @Autowired JobRuns jobRuns;
  @Autowired MeterRegistry meters;
  @Autowired DataSource dataSource;
  @Autowired Retention retention;
  @Autowired Subscriptions subscriptions;
  @Autowired ApplicationContext context;
  @Autowired Environment environment;

  @Test
  @DisplayName("a pharmacy's failure fails the run: recorded, logged JOB_FAILED, counted, shown")
  void failureIsRecorded() {
    String job = "test-failing-" + UUID.randomUUID();
    UUID pharmacy = UUID.randomUUID();
    Logger logger = (Logger) LoggerFactory.getLogger(SingleRunner.class);
    ListAppender<ILoggingEvent> lines = new ListAppender<>();
    lines.start();
    logger.addAppender(lines);
    try {
      assertThat(
              runner.runAlone(
                  job,
                  run -> {
                    run.note("2 pharmacies visited");
                    run.failed(pharmacy, "the provider said no");
                  }))
          .isTrue();
    } finally {
      logger.detachAppender(lines);
    }

    assertThat(lines.list)
        .anySatisfy(
            line -> {
              assertThat(line.getLevel()).isEqualTo(Level.ERROR);
              assertThat(line.getFormattedMessage())
                  .startsWith(SingleRunner.FAILED_PREFIX + " job=" + job)
                  .contains(pharmacy.toString())
                  .contains("the provider said no");
            });
    assertThat(meters.get("pharmacy.jobs.runs").tag("job", job).tag("outcome", "FAILED").counter())
        .satisfies(counter -> assertThat(counter.count()).isEqualTo(1.0));
    assertThat(meters.get("pharmacy.jobs.failures").tag("job", job).counter().count())
        .isEqualTo(1.0);

    Api root = root();
    String at = "$.data[?(@.job == '" + job + "')]";
    Api.Response jobs = root.get("/api/v1/platform/jobs").expect(200);
    assertThat(jobs.read(at + ".lastRun.outcome", List.class)).containsExactly("FAILED");
    assertThat(jobs.read(at + ".lastRun.message", List.class))
        .containsExactly("2 pharmacies visited");
    assertThat(jobs.read(at + ".lastFailure.failures[0].tenantUid", List.class))
        .containsExactly(pharmacy.toString());
    assertThat(jobs.read(at + ".lastFailure.failures[0].error", List.class))
        .containsExactly("the provider said no");
    assertThat(jobs.read(at + ".failedLast24Hours", List.class)).containsExactly(1);
    assertThat(jobs.read(at + ".lastRun.instance", List.class)).doesNotContainNull();

    // Root only.
    api().get("/api/v1/platform/jobs").expect(401);
  }

  @Test
  @DisplayName("a job that throws is FAILED with the error; a later good run is the last run")
  void thrownAndRecovered() {
    String job = "test-throwing-" + UUID.randomUUID();
    assertThat(
            runner.runAlone(
                job,
                run -> {
                  throw new IllegalStateException("database on fire");
                }))
        .isTrue();
    runner.runAlone(job, run -> run.note("fine again"));

    JobRuns.JobStatus status =
        jobRuns.statuses().stream().filter(s -> s.job().equals(job)).findFirst().orElseThrow();
    assertThat(status.lastRun().outcome()).isEqualTo(JobRuns.Outcome.OK);
    assertThat(status.lastRun().finishedAt()).isNotNull();
    assertThat(status.lastSucceededAt()).isNotNull();
    assertThat(status.lastFailure().message()).contains("database on fire");
    assertThat(status.failedLast24Hours()).isEqualTo(1);
    assertThat(jobRuns.recent(job, 10)).hasSize(2);

    Api.Response runs = root().get("/api/v1/platform/jobs/" + job + "/runs").expect(200);
    assertThat(runs.read("$.data[*].outcome", List.class)).containsExactly("OK", "FAILED");
  }

  @Test
  @DisplayName("the scheduled jobs record their runs: subscriptions, retention, event resubmission")
  void scheduledJobsRecord() throws Exception {
    invoke(subscriptions, "daily");
    retention.nightly();
    invoke(context.getBean("eventResubmissionSchedule"), "resubmit");
    invoke(context.getBean("webhookRetrySchedule"), "retry");

    Map<String, JobRuns.JobStatus> byJob =
        jobRuns.statuses().stream()
            .collect(java.util.stream.Collectors.toMap(JobRuns.JobStatus::job, s -> s));
    for (String job :
        List.of("subscription-lifecycle", "retention", "event-resubmission", "webhook-retries")) {
      assertThat(byJob).containsKey(job);
      assertThat(byJob.get(job).lastRun().finishedAt()).as(job).isNotNull();
      assertThat(byJob.get(job).lastRun().outcome()).as(job).isNotNull();
    }
    assertThat(byJob.get("retention").lastRun().message()).startsWith("deleted ");
  }

  @Test
  @DisplayName("the retention keeps 30 days of job runs and drops expired rate windows")
  void retentionTrims() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    String job = "test-old-" + UUID.randomUUID();
    jdbc.update(
        "insert into platform.job_runs (job, instance, started_at, finished_at, outcome)"
            + " values (?, 'old', now() - interval '31 days', now() - interval '31 days', 'OK'),"
            + " (?, 'recent', now() - interval '29 days', now() - interval '29 days', 'OK')",
        job,
        job);
    String key = "test:" + UUID.randomUUID();
    jdbc.update(
        "insert into platform.rate_windows (key, hits, expires_at)"
            + " values (?, '{}', now() - interval '1 hour')",
        key);

    retention.nightly();

    assertThat(jobRuns.recent(job, 10))
        .extracting(JobRuns.RunView::instance)
        .containsOnly("recent");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from platform.rate_windows where key = ?", Long.class, key))
        .isZero();
  }

  @Test
  @DisplayName("Prometheus metrics: jobs, sign-ins, notifications, webhooks and HTTP")
  void prometheus() {
    String job = "test-metrics-" + UUID.randomUUID();
    runner.runAlone(job, run -> {});
    api()
        .post(
            "/api/v1/auth/sign-in",
            "{\"email\":\"nobody-"
                + UUID.randomUUID()
                + "@jobs.test\",\"password\":\"Wrong#2026x\"}")
        .expect(401);

    String scrape = api().get("/actuator/prometheus").expect(200).body();
    assertThat(scrape)
        .contains("pharmacy_jobs_runs_total{job=\"" + job + "\",outcome=\"OK\"} 1.0")
        .contains("pharmacy_jobs_duration_seconds")
        .contains("pharmacy_signin_failures_total")
        .contains("http_server_requests_seconds");
    // Logs: plain text here, JSON (ECS) with PHARMACY_LOG_FORMAT=json.
    assertThat(environment.getProperty("logging.structured.format.console")).isEmpty();
  }

  private Api root() {
    Api anonymous = api();
    return anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
  }

  private static void invoke(Object bean, String name) throws Exception {
    Object target = AopTestUtils.getUltimateTargetObject(bean);
    Method method = target.getClass().getDeclaredMethod(name);
    method.setAccessible(true);
    method.invoke(target);
  }
}
