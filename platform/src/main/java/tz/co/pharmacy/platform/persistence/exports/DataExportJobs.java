package tz.co.pharmacy.platform.persistence.exports;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.platform.kernel.Actor;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.Uids;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * The full-export jobs of the pharmacies ({@code platform.data_exports}). A request records a job
 * ({@link #start}); a worker on any instance claims the next one ({@link #claim}, {@code FOR UPDATE
 * SKIP LOCKED} in {@code platform.claim_data_export}), sends heartbeats while it builds the ZIP and
 * marks it {@link #ready} with its stored file. A job whose worker stops sending heartbeats is
 * claimed again by another, up to the given attempts. A pharmacy has one open job at a time (a
 * unique index): a second {@link #start} is refused with {@code 409 export_running}. Every call is
 * one short transaction; job times are the database's.
 */
@Component
public class DataExportJobs {

  /** How a job is built: by the worker, into a stored file; or by the request, sent at once. */
  public enum Mode {
    STORED,
    STREAMED
  }

  /** One job as its pharmacy sees it. */
  public record Job(
      UUID uid,
      Mode mode,
      String status,
      Instant requestedAt,
      String requestedBy,
      UUID requestedByUid,
      int attempts,
      Instant startedAt,
      Instant finishedAt,
      Instant expiresAt,
      UUID fileUid,
      Long sizeBytes,
      String error) {}

  /** A job claimed by this worker: build it as {@code tenantUid}. */
  public record Claim(UUID uid, UUID tenantUid, int attempts) {}

  /** A READY job past its expiry, whose file is to be deleted. */
  public record Expiring(UUID uid, UUID tenantUid, UUID fileUid) {}

  private static final String COLUMNS =
      "uid, mode, status, requested_at, requested_by, requested_by_uid, attempts, started_at,"
          + " finished_at, expires_at, file_uid, size_bytes, error";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate tx;
  private final AuditLog audit;

  public DataExportJobs(
      DataSource dataSource, PlatformTransactionManager transactions, AuditLog audit) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.tx = new TransactionTemplate(transactions);
    this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.audit = audit;
  }

  /**
   * Records a new job of the pharmacy for the current actor, audited as DATA_EXPORT_REQUESTED: a
   * {@code STORED} one waits for a worker (PENDING), a {@code STREAMED} one is the caller's own,
   * already RUNNING. 409 export_running (with the open job's {@code uid}) while one is open.
   */
  public Job start(UUID tenantUid, Mode mode) {
    Actor actor = ExecutionContext.actor();
    UUID uid = Uids.newUid();
    try {
      return as(
          tenantUid,
          () -> {
            jdbc.update(
                "insert into platform.data_exports (uid, tenant_uid, mode, status, requested_by,"
                    + " requested_by_uid, attempts, started_at, heartbeat_at)"
                    + " values (?, ?, ?, ?, ?, ?, ?, case when ? then now() end,"
                    + " case when ? then now() end)",
                uid,
                tenantUid,
                mode.name(),
                mode == Mode.STREAMED ? "RUNNING" : "PENDING",
                actor.reference(),
                actor.uid(),
                mode == Mode.STREAMED ? 1 : 0,
                mode == Mode.STREAMED,
                mode == Mode.STREAMED);
            audit.record(
                "DATA_EXPORT_REQUESTED",
                "DataExport",
                uid,
                null,
                Map.of("mode", mode.name(), "tenantUid", tenantUid.toString()));
            return find(uid).orElseThrow();
          });
    } catch (DuplicateKeyException running) {
      UUID open =
          as(
              tenantUid,
              () ->
                  jdbc
                      .query(
                          "select uid from platform.data_exports where tenant_uid = ?"
                              + " and status in ('PENDING', 'RUNNING')",
                          (rs, i) -> rs.getObject(1, UUID.class),
                          tenantUid)
                      .stream()
                      .findFirst()
                      .orElse(null));
      Refusal refusal =
          Refusal.conflict(
              "export_running",
              "An export of this pharmacy is being prepared; wait for it to finish.");
      throw open == null ? refusal : refusal.with("uid", open);
    }
  }

  /** The pharmacy's job, if it has one of that uid. */
  public Optional<Job> find(UUID tenantUid, UUID uid) {
    return as(tenantUid, () -> find(uid));
  }

  /** Whether the pharmacy has a job waiting or being built. */
  public boolean open(UUID tenantUid) {
    return as(
        tenantUid,
        () ->
            Boolean.TRUE.equals(
                jdbc.queryForObject(
                    "select exists (select 1 from platform.data_exports where tenant_uid = ?"
                        + " and status in ('PENDING', 'RUNNING'))",
                    Boolean.class,
                    tenantUid)));
  }

  /**
   * Claims the next job of any pharmacy for this worker, or none. A RUNNING job without a heartbeat
   * for {@code staleAfter} is claimed again while it has attempts left, and fails otherwise.
   */
  public Optional<Claim> claim(Duration staleAfter, int maxAttempts) {
    return as(
        null,
        () ->
            jdbc
                .query(
                    "select job_uid, job_tenant_uid, job_attempts from"
                        + " platform.claim_data_export(make_interval(secs => ?), ?)",
                    (rs, i) ->
                        new Claim(
                            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3)),
                    (double) staleAfter.toSeconds(),
                    maxAttempts)
                .stream()
                .findFirst());
  }

  /** The worker is still at it. */
  public void heartbeat(UUID tenantUid, UUID uid) {
    as(
        tenantUid,
        () ->
            jdbc.update(
                "update platform.data_exports set heartbeat_at = now() where uid = ?"
                    + " and status = 'RUNNING'",
                uid));
  }

  /**
   * The ZIP is stored as {@code fileUid}, kept {@code keep}. False when the job is no longer this
   * worker's (it was given up, or the pharmacy erased): the caller then deletes the file.
   */
  public boolean ready(UUID tenantUid, UUID uid, UUID fileUid, long sizeBytes, Duration keep) {
    return as(
            tenantUid,
            () ->
                jdbc.update(
                    "update platform.data_exports set status = 'READY', finished_at = now(),"
                        + " expires_at = now() + make_interval(secs => ?), file_uid = ?,"
                        + " size_bytes = ?, error = null where uid = ? and status = 'RUNNING'",
                    (double) keep.toSeconds(),
                    fileUid,
                    sizeBytes,
                    uid))
        == 1;
  }

  /** A STREAMED job was sent. */
  public void delivered(UUID tenantUid, UUID uid, long sizeBytes) {
    as(
        tenantUid,
        () ->
            jdbc.update(
                "update platform.data_exports set status = 'DELIVERED', finished_at = now(),"
                    + " expires_at = now(), size_bytes = ? where uid = ? and status = 'RUNNING'",
                sizeBytes,
                uid));
  }

  /** The attempt failed; the job waits for another one. */
  public void retry(UUID tenantUid, UUID uid, String error) {
    as(
        tenantUid,
        () ->
            jdbc.update(
                "update platform.data_exports set status = 'PENDING', error = ? where uid = ?"
                    + " and status = 'RUNNING'",
                shortened(error),
                uid));
  }

  /** The job gave up for good; the pharmacy may start another. */
  public void fail(UUID tenantUid, UUID uid, String error) {
    as(
        tenantUid,
        () ->
            jdbc.update(
                "update platform.data_exports set status = 'FAILED', finished_at = now(),"
                    + " expires_at = now(), error = ? where uid = ? and status in ('PENDING',"
                    + " 'RUNNING')",
                shortened(error),
                uid));
  }

  /** READY jobs of every pharmacy past their expiry, oldest first. */
  public List<Expiring> expiring(int limit) {
    return as(
        null,
        () ->
            jdbc.query(
                "select job_uid, job_tenant_uid, job_file_uid from"
                    + " platform.data_exports_to_expire(?)",
                (rs, i) ->
                    new Expiring(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)),
                limit));
  }

  /** The job's file is deleted. */
  public void expired(UUID tenantUid, UUID uid) {
    as(
        tenantUid,
        () ->
            jdbc.update(
                "update platform.data_exports set status = 'EXPIRED' where uid = ?"
                    + " and status = 'READY'",
                uid));
  }

  private Optional<Job> find(UUID uid) {
    return jdbc
        .query(
            "select " + COLUMNS + " from platform.data_exports where uid = ?",
            DataExportJobs::job,
            uid)
        .stream()
        .findFirst();
  }

  private <T> T as(UUID tenantUid, Supplier<T> work) {
    return TenantContext.callAs(tenantUid, () -> tx.execute(status -> work.get()));
  }

  private static Job job(ResultSet rs, int row) throws SQLException {
    return new Job(
        rs.getObject("uid", UUID.class),
        Mode.valueOf(rs.getString("mode")),
        rs.getString("status"),
        instant(rs.getTimestamp("requested_at")),
        rs.getString("requested_by"),
        rs.getObject("requested_by_uid", UUID.class),
        rs.getInt("attempts"),
        instant(rs.getTimestamp("started_at")),
        instant(rs.getTimestamp("finished_at")),
        instant(rs.getTimestamp("expires_at")),
        rs.getObject("file_uid", UUID.class),
        rs.getObject("size_bytes", Long.class),
        rs.getString("error"));
  }

  private static Instant instant(Timestamp at) {
    return at == null ? null : at.toInstant();
  }

  private static String shortened(String error) {
    if (error == null) {
      return null;
    }
    return error.length() > 500 ? error.substring(0, 500) : error;
  }
}
