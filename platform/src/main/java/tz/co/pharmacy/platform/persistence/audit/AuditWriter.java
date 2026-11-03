package tz.co.pharmacy.platform.persistence.audit;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.platform.kernel.Actor;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Uids;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Inserts one audit row on the connection of the transaction under way (the JDBC template joins the
 * JPA transaction's connection), so an entry commits or rolls back with the change it describes.
 */
final class AuditWriter {

  static final JsonMapper JSON = JsonMapper.builder().build();

  private final JdbcTemplate jdbc;

  AuditWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  void insert(
      String action,
      String entityType,
      UUID entityUid,
      Map<String, Object> before,
      Map<String, Object> after) {
    jdbc.update(
        "insert into audit.entries (uid, at, tenant_uid, branch_uid, actor, action, entity_type,"
            + " entity_uid, before, after, correlation_id)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)",
        Uids.newUid(),
        Timestamp.from(Instant.now()),
        TenantContext.tenantUid().orElse(null),
        ExecutionContext.branchUid().orElse(null),
        actor(),
        action,
        entityType,
        entityUid,
        json(before),
        json(after),
        ExecutionContext.correlationId().orElse(null));
  }

  static String actor() {
    return ExecutionContext.current()
        .map(ExecutionContext.Frame::actor)
        .map(Actor::reference)
        .orElse("anonymous");
  }

  private static String json(Map<String, Object> values) {
    return values == null || values.isEmpty() ? null : JSON.writeValueAsString(values);
  }
}
