package tz.co.pharmacy.platform.persistence.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** The audit trail in {@code audit.entries}, read and written as the current scope. */
public class JdbcAuditLog implements AuditLog {

  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

  private final JdbcTemplate jdbc;
  private final AuditWriter writer;

  public JdbcAuditLog(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.writer = new AuditWriter(jdbc);
  }

  @Override
  @Transactional
  public void record(
      String action,
      String entityType,
      UUID entityUid,
      Map<String, Object> before,
      Map<String, Object> after) {
    writer.insert(
        action, entityType, entityUid, AuditValues.masked(before), AuditValues.masked(after));
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AuditEntryView> search(
      String entityType,
      UUID entityUid,
      String actor,
      LocalDate from,
      LocalDate to,
      PageQuery query) {
    StringBuilder sql =
        new StringBuilder(
            "select uid, at, actor, action, entity_type, entity_uid, tenant_uid, branch_uid,"
                + " before::text as before_json, after::text as after_json, correlation_id, label"
                + " from audit.entries where true");
    List<Object> arguments = new ArrayList<>();
    if (entityType != null && !entityType.isBlank()) {
      sql.append(" and lower(entity_type) = lower(?)");
      arguments.add(entityType.trim());
    }
    if (entityUid != null) {
      sql.append(" and entity_uid = ?");
      arguments.add(entityUid);
    }
    if (actor != null && !actor.isBlank()) {
      sql.append(" and lower(actor) like ?");
      arguments.add("%" + actor.trim().toLowerCase().replace("%", "") + "%");
    }
    if (from != null) {
      sql.append(" and at >= ?");
      arguments.add(Timestamp.from(from.atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
    if (to != null) {
      sql.append(" and at < ?");
      arguments.add(Timestamp.from(to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()));
    }
    UUID after = query.afterUid();
    if (after != null) {
      sql.append(" and uid < ?");
      arguments.add(after);
    }
    sql.append(" order by uid desc limit ?");
    arguments.add(query.limit() + 1);
    List<AuditEntryView> rows = jdbc.query(sql.toString(), this::row, arguments.toArray());
    return Page.keyset(rows, query, AuditEntryView::uid);
  }

  private AuditEntryView row(ResultSet rs, int index) throws SQLException {
    return new AuditEntryView(
        rs.getObject("uid", UUID.class),
        rs.getTimestamp("at").toInstant(),
        rs.getString("actor"),
        rs.getString("action"),
        rs.getString("entity_type"),
        rs.getObject("entity_uid", UUID.class),
        rs.getObject("tenant_uid", UUID.class),
        rs.getObject("branch_uid", UUID.class),
        map(rs.getString("before_json")),
        map(rs.getString("after_json")),
        rs.getString("correlation_id"),
        rs.getString("label"));
  }

  private static Map<String, Object> map(String json) {
    return json == null ? null : AuditWriter.JSON.readValue(json, MAP);
  }
}
