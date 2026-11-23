package tz.co.pharmacy.platform.persistence;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The store behind the {@code Idempotency-Key} header (IETF draft "The Idempotency-Key HTTP Header
 * Field"): table {@code platform.idempotency_keys}, one row per pharmacy and key, under the
 * pharmacy's row-level security. Every call runs in its own short transaction, so a reservation is
 * visible to every instance at once: the database, not memory, decides which of two concurrent
 * requests with the same key runs.
 *
 * <ul>
 *   <li>{@link #reserve} inserts the key as IN_PROGRESS, or finds the row already there. A row past
 *       its expiry (24 hours by default) is taken over as a new reservation.
 *   <li>{@link #complete} keeps the response of a 2xx outcome, to be replayed to a retry.
 *   <li>{@link #release} deletes a reservation whose request failed, so the client may retry.
 * </ul>
 *
 * A request whose instance died while it ran leaves its key IN_PROGRESS until the key expires: the
 * money may or may not have moved, so a retry is refused (409) rather than risk moving it twice.
 */
@Component
public class IdempotencyKeys {

  /** What a reservation found. */
  public sealed interface Outcome {}

  /** The key is now this request's: run it. */
  public record Reserved() implements Outcome {}

  /** The first request with this key is still running. */
  public record InProgress() implements Outcome {}

  /** The key was used for another request (method, path, body or user). */
  public record Mismatch() implements Outcome {}

  /** The first request completed: replay its response. */
  public record Replay(int status, String contentType, String body) implements Outcome {}

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final Duration lifetime;

  public IdempotencyKeys(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      Clock clock,
      @Value("${pharmacy.idempotency.lifetime:PT24H}") Duration lifetime) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.clock = clock;
    this.lifetime = lifetime;
  }

  /**
   * Reserves {@code key} for this request in the current tenant, or reports what an earlier request
   * with it left. {@code fingerprint} is the SHA-256 of the body; method, path, fingerprint and
   * user must all match for a replay.
   */
  public Outcome reserve(String key, String method, String path, String fingerprint, UUID userUid) {
    UUID tenant = TenantContext.requireTenant();
    Instant now = clock.instant();
    return tx.execute(
        status -> {
          int taken =
              jdbc.update(
                  "insert into platform.idempotency_keys (tenant_uid, idempotency_key,"
                      + " request_method, request_path, fingerprint, user_uid, state, created_at,"
                      + " expires_at) values (?, ?, ?, ?, ?, ?, 'IN_PROGRESS', ?, ?)"
                      + " on conflict (tenant_uid, idempotency_key) do update set"
                      + " request_method = excluded.request_method,"
                      + " request_path = excluded.request_path,"
                      + " fingerprint = excluded.fingerprint, user_uid = excluded.user_uid,"
                      + " state = 'IN_PROGRESS', response_status = null, response_type = null,"
                      + " response_body = null, completed_at = null,"
                      + " created_at = excluded.created_at, expires_at = excluded.expires_at"
                      + " where platform.idempotency_keys.expires_at <= ?",
                  tenant,
                  key,
                  method,
                  path,
                  fingerprint,
                  userUid,
                  Timestamp.from(now),
                  Timestamp.from(now.plus(lifetime)),
                  Timestamp.from(now));
          if (taken == 1) {
            return new Reserved();
          }
          List<Outcome> found =
              jdbc.query(
                  "select request_method, request_path, fingerprint, user_uid, state,"
                      + " response_status, response_type, response_body"
                      + " from platform.idempotency_keys where idempotency_key = ?",
                  (row, n) -> {
                    boolean same =
                        method.equals(row.getString("request_method"))
                            && path.equals(row.getString("request_path"))
                            && fingerprint.equals(row.getString("fingerprint"))
                            && Objects.equals(userUid, row.getObject("user_uid", UUID.class));
                    if (!same) {
                      return new Mismatch();
                    }
                    if ("COMPLETED".equals(row.getString("state"))) {
                      return new Replay(
                          row.getInt("response_status"),
                          row.getString("response_type"),
                          row.getString("response_body"));
                    }
                    return new InProgress();
                  },
                  key);
          // Gone between the insert and the read (released by its request): it is ours to retry.
          return found.isEmpty() ? new InProgress() : found.getFirst();
        });
  }

  /** Keeps the 2xx response of the reserved request, for a retry to replay. */
  public void complete(String key, int status, String contentType, String body) {
    tx.executeWithoutResult(
        s ->
            jdbc.update(
                "update platform.idempotency_keys set state = 'COMPLETED', response_status = ?,"
                    + " response_type = ?, response_body = ?, completed_at = ?"
                    + " where idempotency_key = ? and state = 'IN_PROGRESS'",
                status,
                contentType,
                body,
                Timestamp.from(clock.instant()),
                key));
  }

  /** Deletes the reservation of a request that failed, so the client may retry with the key. */
  public void release(String key) {
    tx.executeWithoutResult(
        s ->
            jdbc.update(
                "delete from platform.idempotency_keys where idempotency_key = ?"
                    + " and state = 'IN_PROGRESS'",
                key));
  }
}
