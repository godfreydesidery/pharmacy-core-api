package tz.co.pharmacy.modules.files.application;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Makes an upload link single use (adversarial review INT-9): the first PUT of a key claims it in
 * {@code files.upload_claims}, in its own short transaction, so every instance sees the claim; a
 * second PUT of the key is refused with 409 {@code upload_used}, whether the first is still running
 * or the file is complete. A PUT that fails (too large, the store down, the connection dropped)
 * gives its claim back, so the same link may be tried again while it is valid.
 */
@Component
class UploadClaims {

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;

  UploadClaims(DataSource dataSource, PlatformTransactionManager transactions, Clock clock) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
  }

  /** Claims {@code key}, whose link is valid until {@code expires}; 409 upload_used if taken. */
  void claim(String key, Instant expires) {
    Instant now = clock.instant();
    Integer claimed =
        tx.execute(
            status -> {
              // Claims of links that have expired guard nothing any more.
              jdbc.update(
                  "delete from files.upload_claims where storage_key in (select storage_key"
                      + " from files.upload_claims where expires_at < ? limit 100)",
                  Timestamp.from(now));
              return jdbc.update(
                  "insert into files.upload_claims (storage_key, claimed_at, expires_at)"
                      + " values (?, ?, ?) on conflict (storage_key) do nothing",
                  key,
                  Timestamp.from(now),
                  Timestamp.from(expires));
            });
    if (claimed == null || claimed == 0) {
      throw Refusal.conflict(
          "upload_used", "This upload link has been used; start the upload again for a new one.");
    }
  }

  /** Gives the claim back after a PUT that did not store the bytes. */
  void release(String key) {
    tx.executeWithoutResult(
        status -> jdbc.update("delete from files.upload_claims where storage_key = ?", key));
  }
}
