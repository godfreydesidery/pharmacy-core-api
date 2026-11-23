package tz.co.pharmacy.modules.reporting.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.platform.persistence.retention.RetentionTask;

/**
 * The nightly retention's part for the read models (PERF-13): {@code reporting.processed_events}
 * markers older than {@code pharmacy.retention.processed-events-days} (30 by default, at least 1).
 * A marker only turns a redelivered event into a no-op, and events are redelivered within minutes,
 * so an old marker guards nothing. The reporting schema's own definer function deletes them across
 * pharmacies, a batch per short transaction; the platform's {@code purge_expired} does not know
 * this module's tables.
 */
@Component
class ProcessedEventsRetention implements RetentionTask {

  private static final int MAX_ROUNDS = 1_000;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final int days;
  private final int batch;

  ProcessedEventsRetention(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      @Value("${pharmacy.retention.processed-events-days:30}") int days,
      @Value("${pharmacy.retention.batch:5000}") int batch) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.tx = new TransactionTemplate(transactions);
    this.days = days;
    this.batch = batch;
  }

  @Override
  public Map<String, Long> run(Instant now) {
    long deleted = 0;
    for (int round = 0; round < MAX_ROUNDS; round++) {
      Long n =
          tx.execute(
              status ->
                  jdbc.queryForObject(
                      "select reporting.purge_processed_events(?, ?, ?)",
                      Long.class,
                      Timestamp.from(now),
                      days,
                      batch));
      long removed = n == null ? 0 : n;
      deleted += removed;
      if (removed < batch) {
        break;
      }
    }
    return Map.of("reporting.processed_events", deleted);
  }
}
