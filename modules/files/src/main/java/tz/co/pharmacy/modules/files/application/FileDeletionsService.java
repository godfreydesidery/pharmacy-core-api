package tz.co.pharmacy.modules.files.application;

import java.util.List;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.files.ports.inbound.FileDeletions;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;

/**
 * Deletes objects from the store, keeping the ones it could not delete in {@code
 * files.pending_deletes} for {@link #retryDue}. The store is never called inside a database
 * transaction: a queued delete is claimed in one short transaction (which pushes its next attempt
 * out, so the claim of an instance that dies simply comes due again), tried, then removed or noted
 * in another.
 */
@Service
class FileDeletionsService implements FileDeletions {

  private static final Logger LOG = LoggerFactory.getLogger(FileDeletionsService.class);

  private final ObjectStorage storage;
  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;

  FileDeletionsService(
      ObjectStorage storage, DataSource dataSource, PlatformTransactionManager transactions) {
    this.storage = storage;
    this.jdbc = new JdbcTemplate(dataSource);
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  @Override
  public boolean delete(String storageKey, String reason) {
    try {
      storage.delete(storageKey);
      return true;
    } catch (RuntimeException failure) {
      LOG.warn(
          "Could not delete {} from the store; queued for a retry: {}",
          storageKey,
          failure.toString());
      tx.executeWithoutResult(
          status ->
              jdbc.update(
                  "insert into files.pending_deletes (storage_key, reason, last_error)"
                      + " values (?, ?, ?) on conflict (storage_key) do update"
                      + " set last_error = excluded.last_error",
                  storageKey,
                  reason == null ? "delete" : reason,
                  message(failure)));
      return false;
    }
  }

  @Override
  public RetryOutcome retryDue(int limit) {
    List<Queued> due =
        tx.execute(
            status ->
                jdbc.query(
                    "update files.pending_deletes p set attempts = p.attempts + 1,"
                        + " next_attempt_at = now() + least(interval '1 hour' * power(2,"
                        + " p.attempts), interval '1 day')"
                        + " where p.id in (select id from files.pending_deletes"
                        + " where next_attempt_at <= now() order by id limit ?"
                        + " for update skip locked) returning p.id, p.storage_key",
                    (rs, i) -> new Queued(rs.getLong(1), rs.getString(2)),
                    limit));
    long deleted = 0;
    long failed = 0;
    for (Queued queued : due == null ? List.<Queued>of() : due) {
      try {
        storage.delete(queued.key());
        tx.executeWithoutResult(
            status -> jdbc.update("delete from files.pending_deletes where id = ?", queued.id()));
        deleted++;
      } catch (RuntimeException failure) {
        tx.executeWithoutResult(
            status ->
                jdbc.update(
                    "update files.pending_deletes set last_error = ? where id = ?",
                    message(failure),
                    queued.id()));
        failed++;
      }
    }
    if (deleted + failed > 0) {
      LOG.info("Queued file deletes: {} done, {} failed again", deleted, failed);
    }
    return new RetryOutcome(deleted, failed);
  }

  private static String message(RuntimeException failure) {
    String text = failure.toString();
    return text.length() > 500 ? text.substring(0, 500) : text;
  }

  private record Queued(long id, String key) {}
}
