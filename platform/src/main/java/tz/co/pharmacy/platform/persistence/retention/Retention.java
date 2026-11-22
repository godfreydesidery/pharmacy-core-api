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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deletes what is no longer needed, every night (Tanzania's Personal Data Protection Act 2022,
 * storage limitation): expired sign-in sessions and codes, old invitations, the message and webhook
 * logs, read in-app notifications, completed internal events, and audit months older than the audit
 * retention period. Each period is configurable under {@code pharmacy.retention}. The deleting is
 * done by {@code platform.purge_expired} and {@code audit.drop_partitions_before}, which the
 * application role may call but whose tables it may not delete from itself.
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

  public Retention(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      Clock clock,
      @Value("${pharmacy.retention.sessions-days:30}") int sessions,
      @Value("${pharmacy.retention.codes-days:7}") int codes,
      @Value("${pharmacy.retention.invitations-days:90}") int invitations,
      @Value("${pharmacy.retention.messages-days:180}") int messages,
      @Value("${pharmacy.retention.inbox-days:180}") int inbox,
      @Value("${pharmacy.retention.deliveries-days:90}") int deliveries,
      @Value("${pharmacy.retention.events-days:7}") int events,
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
    this.auditYears = auditYears;
    this.batch = batch;
  }

  @Scheduled(cron = "${pharmacy.retention.cron:0 40 1 * * *}", zone = "UTC")
  public void nightly() {
    try {
      run();
    } catch (RuntimeException failure) {
      log.warn("Retention run failed: {}", failure.getMessage());
    }
  }

  /**
   * One full run: rows deleted per table (and audit months dropped), in short transactions so
   * nothing is locked for long. The periods count back from the application clock.
   */
  public Map<String, Long> run() {
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
    log.info("Retention run (as of {}): {}", now, totals);
    return totals;
  }
}
