package tz.co.pharmacy.platform.persistence.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
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
  // Looked up on use: the modules that name records depend on this log themselves.
  private final ObjectProvider<AuditLabels> labels;

  public JdbcAuditLog(JdbcTemplate jdbc, ObjectProvider<AuditLabels> labels) {
    this.jdbc = jdbc;
    this.writer = new AuditWriter(jdbc);
    this.labels = labels;
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
      String action,
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
    if (action != null && !action.isBlank()) {
      sql.append(" and upper(action) = upper(?)");
      arguments.add(action.trim());
    }
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
    if (query.numbered()) {
      // By page number: the same filter counted, then the page at its offset.
      String filtered = sql.toString();
      Long total =
          jdbc.queryForObject(
              "select count(*) from (" + filtered + ") counted", Long.class, arguments.toArray());
      arguments.add(query.limit());
      arguments.add(query.offset());
      List<AuditEntryView> rows =
          jdbc.query(
              filtered + " order by uid desc limit ? offset ?", this::row, arguments.toArray());
      return Page.numbered(labelled(rows), total == null ? 0 : total);
    }
    UUID after = query.afterUid();
    if (after != null) {
      sql.append(" and uid < ?");
      arguments.add(after);
    }
    sql.append(" order by uid desc limit ?");
    arguments.add(query.limit() + 1);
    List<AuditEntryView> rows = jdbc.query(sql.toString(), this::row, arguments.toArray());
    return Page.keyset(labelled(rows), query, AuditEntryView::uid);
  }

  @Override
  @Transactional(readOnly = true)
  public Page<AuditEntryView> securityEvents(UUID userUid, PageQuery query) {
    String filtered =
        "select uid, at, actor, action, entity_type, entity_uid, tenant_uid, branch_uid,"
            + " before::text as before_json, after::text as after_json, correlation_id, label"
            + " from audit.entries where entity_type = 'User' and entity_uid = ?"
            + " and action not in ('CREATE', 'UPDATE', 'DELETE')";
    if (query.numbered()) {
      Long total =
          jdbc.queryForObject(
              "select count(*) from (" + filtered + ") counted", Long.class, userUid);
      List<AuditEntryView> rows =
          jdbc.query(
              filtered + " order by uid desc limit ? offset ?",
              this::row,
              userUid,
              query.limit(),
              query.offset());
      return Page.numbered(rows, total == null ? 0 : total);
    }
    UUID after = query.afterUid();
    List<AuditEntryView> rows =
        after == null
            ? jdbc.query(
                filtered + " order by uid desc limit ?", this::row, userUid, query.limit() + 1)
            : jdbc.query(
                filtered + " and uid < ? order by uid desc limit ?",
                this::row,
                userUid,
                after,
                query.limit() + 1);
    return Page.keyset(rows, query, AuditEntryView::uid);
  }

  /** Fills in the label of entries written without one, from the module owning the record. */
  private List<AuditEntryView> labelled(List<AuditEntryView> rows) {
    Map<String, Set<UUID>> missing = new HashMap<>();
    for (AuditEntryView row : rows) {
      if (row.label() == null && row.entityType() != null && row.entityUid() != null) {
        missing.computeIfAbsent(row.entityType(), type -> new HashSet<>()).add(row.entityUid());
      }
    }
    if (missing.isEmpty()) {
      return rows;
    }
    Map<String, Map<UUID, String>> found = new HashMap<>();
    labels.forEach(
        source -> {
          Set<UUID> uids = missing.get(source.entityType());
          if (uids != null) {
            found.put(source.entityType(), source.labels(uids));
          }
        });
    if (found.isEmpty()) {
      return rows;
    }
    List<AuditEntryView> result = new ArrayList<>(rows.size());
    for (AuditEntryView row : rows) {
      String label =
          row.label() != null || row.entityType() == null
              ? null
              : found.getOrDefault(row.entityType(), Map.of()).get(row.entityUid());
      result.add(
          label == null
              ? row
              : new AuditEntryView(
                  row.uid(),
                  row.at(),
                  row.actor(),
                  row.action(),
                  row.entityType(),
                  row.entityUid(),
                  row.tenantUid(),
                  row.branchUid(),
                  row.before(),
                  row.after(),
                  row.correlationId(),
                  label));
    }
    return result;
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
