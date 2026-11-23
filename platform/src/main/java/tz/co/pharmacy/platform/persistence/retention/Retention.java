package tz.co.pharmacy.platform.persistence.retention;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.jobs.JobRun;

/**
 * Deletes what is no longer needed, every night (Tanzania's Personal Data Protection Act 2022,
 * storage limitation): expired sign-in sessions and codes, old invitations, the message and webhook
 * logs, read in-app notifications, completed internal events, and audit months older than the audit
 * retention period. Each period is configurable under {@code pharmacy.retention}. The deleting is
 * done by {@code platform.purge_expired} and {@code audit.drop_partitions_before}, which the
 * application role may call but whose tables it may not delete from itself.
 *
 * <p>The functions count every period back from the database's {@code now()} at the latest, keep
 * the audit trail at least {@code audit.minimum_retention()} (2 years) whatever is configured, and
 * leave a pharmacy under a legal hold alone. After them, each {@link RetentionTask} runs (expired
 * export files, file deletes to retry, job runs and rate windows past their time). The nightly run
 * happens on one instance only ({@link SingleRunner}), and a task that fails (or a pharmacy a task
 * failed for) is recorded with the run in {@code platform.job_runs}.
 */
@Component
public class Retention {

  private static final Logger log = LoggerFactory.getLogger(Retention.class);
  private static final int MAX_ROUNDS = 10_000;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Map<String, Integer> days = new LinkedHashMap<>();
  private final int auditYears;
  private final int batch;
  private final SingleRunner runner;
  private final ObjectProvider<RetentionTask> tasks;

  public Retention(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      Clock clock,
      SingleRunner runner,
      ObjectProvider<RetentionTask> tasks,
      @Value("${pharmacy.retention.sessions-days:30}") int sessions,
      @Value("${pharmacy.retention.codes-days:7}") int codes,
      @Value("${pharmacy.retention.invitations-days:90}") int invitations,
      @Value("${pharmacy.retention.messages-days:180}") int messages,
      @Value("${pharmacy.retention.inbox-days:180}") int inbox,
      @Value("${pharmacy.retention.deliveries-days:90}") int deliveries,
      @Value("${pharmacy.retention.events-days:7}") int events,
      @Value("${pharmacy.retention.exports-days:30}") int exports,
      @Value("${pharmacy.retention.audit-years:7}") int auditYears,
      @Value("${pharmacy.retention.batch:5000}") int batch) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.tx = new TransactionTemplate(transactions);
    this.clock = clock;
    days.put("sessions", sessions);
    days.put("codes", codes);
    days.put("invitations", invitations);
    days.put("messages", messages);
    days.put("inbox", inbox);
    days.put("deliveries", deliveries);
    days.put("events", events);
    days.put("exports", exports);
    this.auditYears = auditYears;
    this.batch = batch;
    this.runner = runner;
    this.tasks = tasks;
  }

  @Scheduled(cron = "${pharmacy.retention.cron:0 40 1 * * *}", zone = "UTC")
  public void nightly() {
    runner.runAlone(
        "retention",
        run -> {
          Map<String, Long> totals = run(run);
          run.note("deleted " + totals);
        });
  }

  /**
   * One full run: rows deleted per table (and audit months dropped), in short transactions so
   * nothing is locked for long. The periods count back from the application clock.
   */
  public Map<String, Long> run() {
    return run(new JobRun("retention"));
  }

  /** The same, recording each task that fails in {@code run} instead of dropping it. */
  public Map<String, Long> run(JobRun run) {
    Instant now = clock.instant();
    String periods = json.writeValueAsString(days);
    Map<String, Long> totals = new LinkedHashMap<>();
    for (int round = 0; round < MAX_ROUNDS; round++) {
      String answer =
          tx.execute(
              status ->
                  jdbc.queryForObject(
                      "select platform.purge_expired(?, ?::jsonb, ?)::text",
                      String.class,
                      Timestamp.from(now),
                      periods,
                      batch));
      Map<String, Long> counts = json.readValue(answer, new TypeReference<Map<String, Long>>() {});
      long deleted = counts.getOrDefault("total", 0L);
      counts.remove("total");
      counts.forEach((table, n) -> totals.merge(table, n, Long::sum));
      if (deleted == 0) {
        break;
      }
    }
    LocalDate keepFrom = LocalDate.ofInstant(now, ZoneOffset.UTC).minusYears(auditYears);
    Integer months =
        tx.execute(
            status ->
                jdbc.queryForObject(
                    "select audit.drop_partitions_before(?)",
                    Integer.class,
                    Date.valueOf(keepFrom)));
    totals.put("audit months dropped", months == null ? 0L : months.longValue());
    tasks
        .orderedStream()
        .forEach(
            task -> {
              try {
                task.run(now, run).forEach((name, n) -> totals.merge(name, n, Long::sum));
              } catch (RuntimeException failure) {
                log.warn("Retention task {} failed: {}", task.getClass().getSimpleName(), failure);
                run.failed(null, task.getClass().getSimpleName() + ": " + failure);
              }
            });
    log.info("Retention run (as of {}): {}", now, totals);
    return totals;
  }
}
