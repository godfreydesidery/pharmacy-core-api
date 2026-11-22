package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Records name the account behind them (created_by_uid, updated_by_uid) beside the email snapshot,
 * and platform.add_actor_uids fills them for rows written before: through forced row-level security
 * and append-only triggers, which it restores.
 */
class ActorUidsTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  @Test
  @DisplayName("add_actor_uids adds and fills the account columns, then restores the guards")
  void backfill() throws SQLException {
    String schema = "actor_" + UUID.randomUUID().toString().substring(0, 8);
    try (Connection connection = DriverManager.getConnection(url, owner, ownerPassword)) {
      JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      UUID root =
          db.queryForObject(
              "select uid from access.users where email = 'root@test.local'", UUID.class);
      db.execute("create schema " + schema);
      try {
        db.execute(
            "create table "
                + schema
                + ".things (id int primary key, created_by text not null, updated_by text)");
        db.execute("insert into " + schema + ".things values (1, 'root@test.local', null)");
        db.execute(
            "insert into " + schema + ".things values (2, 'service:seed', 'root@test.local')");
        db.execute("alter table " + schema + ".things enable row level security");
        db.execute("alter table " + schema + ".things force row level security");
        db.execute(
            "create policy nobody on " + schema + ".things using (false) with check (false)");
        db.execute(
            "create trigger append_only before update or delete on "
                + schema
                + ".things for each row execute function platform.refuse_change()");

        db.execute("select platform.add_actor_uids('" + schema + "')");

        // Forced, the deny-all policy hides every row even from the owner: unforce to read.
        db.execute("alter table " + schema + ".things no force row level security");
        assertThat(
                db.queryForObject(
                    "select created_by_uid from " + schema + ".things where id = 1", UUID.class))
            .isEqualTo(root);
        assertThat(
                db.queryForObject(
                    "select created_by_uid is null and updated_by_uid = ? from "
                        + schema
                        + ".things where id = 2",
                    Boolean.class,
                    root))
            .isTrue();
        db.execute("alter table " + schema + ".things force row level security");

        assertThat(
                db.queryForObject(
                    "select relforcerowsecurity from pg_class where oid = ?::regclass",
                    Boolean.class,
                    schema + ".things"))
            .isTrue();
        assertThat(
                db.queryForObject(
                    "select tgenabled from pg_trigger where tgname = 'append_only'"
                        + " and tgrelid = ?::regclass",
                    String.class,
                    schema + ".things"))
            .isEqualTo("O");
      } finally {
        db.execute("drop schema " + schema + " cascade");
      }
    }
  }

  @Test
  @DisplayName("every table that records who created it also records the account")
  void everyRecordingTableHasTheAccountColumns() throws SQLException {
    try (Connection connection = DriverManager.getConnection(url, owner, ownerPassword)) {
      JdbcTemplate db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      Integer missing =
          db.queryForObject(
              """
              select count(*) from information_schema.columns c
              join pg_class t on t.relname = c.table_name
              join pg_namespace n on n.oid = t.relnamespace and n.nspname = c.table_schema
              where c.column_name = 'created_by' and t.relkind in ('r', 'p') and not t.relispartition
                and c.table_schema not in ('pg_catalog', 'information_schema')
                and c.table_schema not like 'actor\\_%'
                and not exists (select 1 from information_schema.columns u
                                where u.table_schema = c.table_schema
                                  and u.table_name = c.table_name
                                  and u.column_name = 'created_by_uid')
              """,
              Integer.class);
      assertThat(missing).isZero();
    }
  }
}
