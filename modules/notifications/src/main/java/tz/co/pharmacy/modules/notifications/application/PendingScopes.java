package tz.co.pharmacy.modules.notifications.application;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Which scopes have messages due for another attempt, so the retry job visits only those, each in
 * its own tenant. The platform's scope is the zero uid.
 */
@Component
class PendingScopes {

  static final UUID PLATFORM = new UUID(0L, 0L);

  private final JdbcTemplate jdbc;

  PendingScopes(DataSource dataSource) {
    this.jdbc = new JdbcTemplate(dataSource);
  }

  static UUID scopeOf(UUID tenantUid) {
    return tenantUid == null ? PLATFORM : tenantUid;
  }

  static UUID tenantOf(UUID scopeUid) {
    return PLATFORM.equals(scopeUid) ? null : scopeUid;
  }

  /** Records that the scope has a message due at {@code dueAt}, keeping an earlier one. */
  void due(UUID scopeUid, Instant dueAt) {
    jdbc.update(
        "insert into notifications.pending_scopes (scope_uid, next_attempt_at) values (?, ?)"
            + " on conflict (scope_uid) do update set next_attempt_at ="
            + " least(notifications.pending_scopes.next_attempt_at, excluded.next_attempt_at)",
        scopeUid,
        Timestamp.from(dueAt));
  }

  /** Sets the scope's earliest due message, or forgets the scope when none is left. */
  void settle(UUID scopeUid, Instant earliest) {
    if (earliest == null) {
      jdbc.update("delete from notifications.pending_scopes where scope_uid = ?", scopeUid);
    } else {
      jdbc.update(
          "insert into notifications.pending_scopes (scope_uid, next_attempt_at) values (?, ?)"
              + " on conflict (scope_uid) do update set next_attempt_at = excluded.next_attempt_at",
          scopeUid,
          Timestamp.from(earliest));
    }
  }

  List<UUID> dueBy(Instant now) {
    return jdbc.queryForList(
        "select scope_uid from notifications.pending_scopes where next_attempt_at <= ?"
            + " order by next_attempt_at limit 500",
        UUID.class,
        Timestamp.from(now));
  }
}
