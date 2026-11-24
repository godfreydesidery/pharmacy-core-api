package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * The nightly retention deletes what has outlived its period, and nothing else: an expired session
 * goes while a live one keeps working; old reset links, invitations, sent messages and completed
 * events go while fresh ones stay; an audit month older than the retention period is dropped while
 * this month's stays.
 */
class RetentionTest extends IntegrationTest {

  @Autowired DataSource dataSource;

  @Test
  @DisplayName(
      "old sessions, links, invitations, messages, events and audit months go; fresh ones stay")
  void purgesOnlyWhatOutlivedItsPeriod() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "RT" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Keep %s","legalName":"Keep %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");

    // A session that expired 40 days ago, and a live one.
    Api old = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong"));
    sql(
        null,
        "update access.sessions set expires_at = now() - interval '40 days' where user_uid ="
            + " (select uid from access.users where email = ?)",
        email);
    Api live = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);

    // A reset link 10 days past its expiry, and its email (sent long ago).
    anonymous.post("/api/v1/auth/password-reset", "{\"email\":\"" + email + "\"}").expect(202);
    eventually(
        () ->
            count(
                    null,
                    "select count(*) from notifications.messages where to_address = ? and status = 'SENT'",
                    email)
                > 0);
    sql(
        null,
        "update access.password_resets set expires_at = now() - interval '10 days' where user_uid ="
            + " (select uid from access.users where email = ?)",
        email);
    sql(
        null,
        "update notifications.messages set created_at = now() - interval '200 days' where to_address = ?",
        email);

    // Two invitations: one expired long ago, one fresh.
    List<String> roles =
        live.get("/api/v1/roles").expect(200).json().read("$.data[?(@.code == 'CASHIER')].uid");
    String stale = "stale." + code.toLowerCase() + "@test.local";
    String fresh = "fresh." + code.toLowerCase() + "@test.local";
    for (String invitee : List.of(stale, fresh)) {
      live.post(
              "/api/v1/users/invitations",
              """
              {"name":"Invitee","email":"%s","roleUid":"%s","allBranches":true}
              """
                  .formatted(invitee, roles.getFirst()))
          .expect(201);
    }
    sql(
        tenant,
        "update access.invitations set expires_at = now() - interval '100 days' where email = ?",
        stale);

    // Completed events from all of the above, made old; and an audit month from 2015.
    eventually(
        () ->
            count(
                    null,
                    "select count(*) from platform.event_publication where completion_date is not null")
                > 0);
    sql(
        null,
        "update platform.event_publication set completion_date = now() - interval '30 days' where completion_date is not null");
    sql(null, "select audit.ensure_partitions('2015-01-01'::date, 0)");
    assertThat(count(null, "select count(*) from pg_class where relname = 'entries_2015_01'"))
        .isOne();

    Api.Response run = root.post("/api/v1/platform/retention/run", "{}").expect(200);

    assertThat(run.read("$.data['access.sessions']", Long.class)).isPositive();
    assertThat(run.read("$.data['audit months dropped']", Long.class)).isPositive();
    old.get("/api/v1/auth/me").expect(401);
    live.get("/api/v1/auth/me").expect(200);
    assertThat(
            count(
                null,
                "select count(*) from access.password_resets where user_uid = (select uid from access.users where email = ?)",
                email))
        .isZero();
    assertThat(
            count(null, "select count(*) from notifications.messages where to_address = ?", email))
        .isZero();
    assertThat(count(tenant, "select count(*) from access.invitations where email = ?", stale))
        .isZero();
    assertThat(count(tenant, "select count(*) from access.invitations where email = ?", fresh))
        .isOne();
    assertThat(
            count(
                null,
                "select count(*) from platform.event_publication where completion_date < now() - interval '7 days'"))
        .isZero();
    assertThat(count(null, "select count(*) from pg_class where relname = 'entries_2015_01'"))
        .isZero();
    assertThat(
            count(
                null,
                "select count(*) from pg_inherits where inhparent = 'audit.entries'::regclass"))
        .isGreaterThan(1);
    // The pharmacy carries on.
    live.post("/api/v1/customers", "{\"name\":\"After cleanup\",\"phone\":\"+255700000120\"}")
        .expect(201);
  }

  // ------------------------------------------------------------------ helpers

  /** Runs a statement as the application role, as the pharmacy (or as the platform when null). */
  private void sql(String tenantUid, String statement, Object... args) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      scope(c, tenantUid);
      try (PreparedStatement s = c.prepareStatement(statement)) {
        for (int i = 0; i < args.length; i++) {
          s.setObject(i + 1, args[i]);
        }
        s.execute();
      }
      c.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private long count(String tenantUid, String query, Object... args) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      scope(c, tenantUid);
      try (PreparedStatement s = c.prepareStatement(query)) {
        for (int i = 0; i < args.length; i++) {
          s.setObject(i + 1, args[i]);
        }
        try (ResultSet r = s.executeQuery()) {
          r.next();
          long n = r.getLong(1);
          c.rollback();
          return n;
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void scope(Connection c, String tenantUid) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
      s.setString(1, tenantUid == null ? "" : tenantUid);
      s.execute();
    }
  }
}
