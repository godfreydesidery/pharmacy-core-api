package tz.co.pharmacy.platform.persistence.audit;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.platform.kernel.Actor;
import tz.co.pharmacy.platform.kernel.Clocks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Uids;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Writes the audit rows of a Hibernate session on the connection of the transaction under way (the
 * JDBC template joins the JPA transaction's connection), so an entry commits or rolls back with the
 * change it describes.
 *
 * <p>The rows are written in batches (PERF-7): each change's row is taken down when the change
 * happens (its time, tenant, branch, actor and correlation id then), kept with the session, and
 * written in one JDBC batch when the session flushes in full (an explicit flush, the flush before
 * commit, and the flush {@code TenantSwitch} makes before it changes the tenant, so every row is
 * written under the tenant it belongs to) or, failing that, just before the transaction commits. A
 * rolled-back transaction writes none, as before.
 */
final class AuditWriter {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private static final String INSERT =
      "insert into audit.entries (uid, at, tenant_uid, branch_uid, actor, actor_uid, action,"
          + " entity_type, entity_uid, before, after, correlation_id, label)"
          + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)";

  /** The rows waiting per session; a thread holds its sessions only (one per transaction). */
  private static final ThreadLocal<Map<SharedSessionContractImplementor, List<Object[]>>> WAITING =
      ThreadLocal.withInitial(IdentityHashMap::new);

  private final JdbcTemplate jdbc;

  AuditWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Writes one entry now (an explicit entry of {@link AuditLog}, not a session's change). */
  void insert(
      String action,
      String entityType,
      UUID entityUid,
      Map<String, Object> before,
      Map<String, Object> after) {
    jdbc.update(
        INSERT,
        row(action, entityType, entityUid, before, after, AuditValues.label(after, before)));
  }

  /**
   * Takes down one row for the session's next batch. {@code label}: a human name for the record
   * (document number, name, email, code), or null.
   */
  void insert(
      SharedSessionContractImplementor session,
      String action,
      String entityType,
      UUID entityUid,
      Map<String, Object> before,
      Map<String, Object> after,
      String label) {
    Object[] row = row(action, entityType, entityUid, before, after, label);
    Map<SharedSessionContractImplementor, List<Object[]>> sessions = WAITING.get();
    List<Object[]> rows = sessions.get(session);
    if (rows == null) {
      rows = new ArrayList<>();
      sessions.put(session, rows);
      // Before commit, whatever no full flush wrote; after the transaction, nothing is kept.
      session.getTransactionCompletionCallbacks().registerCallback(this::write);
      session
          .getTransactionCompletionCallbacks()
          .registerCallback(
              (org.hibernate.engine.spi.TransactionCompletionCallbacks.AfterCompletionCallback)
                  (success, completed) -> forget(completed));
    }
    rows.add(row);
  }

  /** The row's values as the change is made: its time and who, where and for whom. */
  private static Object[] row(
      String action,
      String entityType,
      UUID entityUid,
      Map<String, Object> before,
      Map<String, Object> after,
      String label) {
    return new Object[] {
      Uids.newUid(),
      Timestamp.from(Clocks.now()),
      TenantContext.tenantUid().orElse(null),
      ExecutionContext.branchUid().orElse(null),
      actor(),
      actorUid(),
      action,
      entityType,
      entityUid,
      json(before),
      json(after),
      ExecutionContext.correlationId().orElse(null),
      label
    };
  }

  /** Writes the session's waiting rows in one batch. */
  void write(SharedSessionContractImplementor session) {
    List<Object[]> rows = WAITING.get().get(session);
    if (rows == null || rows.isEmpty()) {
      return;
    }
    List<Object[]> batch = List.copyOf(rows);
    rows.clear();
    if (batch.size() == 1) {
      jdbc.update(INSERT, batch.getFirst());
    } else {
      jdbc.batchUpdate(INSERT, batch);
    }
  }

  private static void forget(SharedSessionContractImplementor session) {
    Map<SharedSessionContractImplementor, List<Object[]>> sessions = WAITING.get();
    sessions.remove(session);
    if (sessions.isEmpty()) {
      WAITING.remove();
    }
  }

  static String actor() {
    return ExecutionContext.current()
        .map(ExecutionContext.Frame::actor)
        .map(Actor::reference)
        .orElse("anonymous");
  }

  /** The account behind the entry; null for a platform service or no one signed in. */
  static UUID actorUid() {
    return ExecutionContext.current()
        .map(ExecutionContext.Frame::actor)
        .map(Actor::uid)
        .orElse(null);
  }

  private static String json(Map<String, Object> values) {
    return values == null || values.isEmpty() ? null : JSON.writeValueAsString(values);
  }
}
