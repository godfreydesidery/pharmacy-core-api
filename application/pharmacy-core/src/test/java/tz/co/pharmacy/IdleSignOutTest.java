package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * A session unused for the pharmacy's inactivity limit (15 minutes unless an admin sets 5 to 60)
 * ends, and the audit log says why. Only the web app's activity reports restart the clock; other
 * requests do not, as background ones run with nobody at the screen.
 */
class IdleSignOutTest extends IntegrationTest {

  @Autowired DataSource dataSource;

  private Api anonymous;
  private Api root;
  private String tenant;
  private String email;
  private Api admin;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "ID" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Idle %s","legalName":"Idle %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = signInAdmin();
  }

  private Api signInAdmin() {
    return anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
  }

  /** As if the admin last used the app {@code minutes} ago. */
  private void lastUsed(int minutes) {
    new JdbcTemplate(dataSource)
        .update(
            "update access.sessions set active_at = now() - make_interval(mins => ?)"
                + " where revoked_at is null"
                + " and user_uid = (select uid from access.users where email = ?)",
            minutes,
            email);
  }

  @Test
  @DisplayName("a pharmacy starts at 15 minutes; an admin may set 5 to 60")
  void setting() {
    assertThat(admin.get("/api/v1/settings").expect(200).string("$.data.idleMinutes"))
        .isEqualTo("15");
    admin.put("/api/v1/settings", "{\"idleMinutes\":30}").expect(200);
    assertThat(admin.get("/api/v1/settings").expect(200).string("$.data.idleMinutes"))
        .isEqualTo("30");
    for (int wrong : new int[] {4, 61}) {
      assertThat(
              admin
                  .put("/api/v1/settings", "{\"idleMinutes\":" + wrong + "}")
                  .expect(422)
                  .string("$.reason"))
          .isEqualTo("invalid_idle_minutes");
    }
    // Saving other settings keeps it.
    admin.put("/api/v1/settings", "{\"receiptFooter\":\"Asante\"}").expect(200);
    assertThat(admin.get("/api/v1/settings").expect(200).string("$.data.idleMinutes"))
        .isEqualTo("30");
  }

  @Test
  @DisplayName("activity in a pharmacy takes its limit; a root administrator keeps 15 minutes")
  void activityTakesThePharmacysLimit() {
    admin.put("/api/v1/settings", "{\"idleMinutes\":25}").expect(200);
    assertThat(admin.post("/api/v1/auth/activity", "{}").expect(200).string("$.data.idleMinutes"))
        .isEqualTo("25");
    assertThat(
            root.in(tenant, null)
                .post("/api/v1/auth/activity", "{}")
                .expect(200)
                .string("$.data.idleMinutes"))
        .isEqualTo("15");
  }

  @Test
  @DisplayName(
      "a session unused past the limit ends with 401 session_idle, audited in the pharmacy")
  void idleSessionEnds() {
    admin.post("/api/v1/auth/activity", "{}").expect(200);
    lastUsed(16);

    assertThat(admin.get("/api/v1/settings").expect(401).string("$.reason"))
        .isEqualTo("session_idle");
    // It stays ended.
    assertThat(admin.get("/api/v1/settings").expect(401).string("$.reason"))
        .isEqualTo("session_expired");

    List<Map<String, Object>> idle =
        signInAdmin()
            .get("/api/v1/audit?action=SIGN_OUT&limit=20")
            .expect(200)
            .json()
            .read("$.data.items[?(@.after.reason == 'IDLE')]");
    assertThat(idle).hasSize(1);
  }

  @Test
  @DisplayName("requests do not keep a session alive; reported activity does")
  void onlyActivityRestartsTheClock() {
    lastUsed(14);
    admin.get("/api/v1/settings").expect(200);
    admin.post("/api/v1/auth/activity", "{}").expect(200);
    // Activity a moment ago: 14 more minutes of plain requests are still fine...
    admin.get("/api/v1/settings").expect(200);
    lastUsed(14);
    admin.get("/api/v1/settings").expect(200);
    // ...and requests alone never move it on. (Past the limit, not on it: at exactly 15:00 the
    // test's database clock and the server's can differ by a fraction of a millisecond.)
    lastUsed(16);
    admin.get("/api/v1/settings").expect(401);
  }

  @Test
  @DisplayName("the app's own sign-out after the limit is audited as idle")
  void appSignOutIsAuditedAsIdle() {
    // The admin worked in the pharmacy, then the app signed them out.
    admin.get("/api/v1/settings").expect(200);
    admin.post("/api/v1/auth/sign-out", "{\"reason\":\"IDLE\"}").expect(200);
    admin.get("/api/v1/settings").expect(401);
    List<Map<String, Object>> idle =
        signInAdmin()
            .get("/api/v1/audit?action=SIGN_OUT&limit=20")
            .expect(200)
            .json()
            .read("$.data.items[?(@.after.reason == 'IDLE')]");
    assertThat(idle).hasSize(1);
  }

  @Test
  @DisplayName(
      "AUTH-17 each request is held to its pharmacy's limit, whatever pharmacy the app reports in")
  void eachRequestKeepsItsPharmacysLimit() {
    admin.put("/api/v1/settings", "{\"idleMinutes\":5}").expect(200);

    // A script reports activity outside the pharmacy (the platform's 15 minutes) and works in it.
    String token = anonymous.signIn(email, "Admin#2026x-strong");
    Api outside = anonymous.as(token);
    Api inside = outside.in(tenant, null);
    assertThat(outside.post("/api/v1/auth/activity", "{}").expect(200).string("$.data.idleMinutes"))
        .isEqualTo("15");
    lastUsed(6);
    // Past the pharmacy's 5 minutes: its requests end the session.
    assertThat(inside.get("/api/v1/settings").expect(401).string("$.reason"))
        .isEqualTo("session_idle");
    outside.get("/api/v1/auth/me").expect(401);

    // A request in the pharmacy makes its stricter limit the session's, wherever it reports next.
    Api fresh = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong"));
    fresh.in(tenant, null).get("/api/v1/settings").expect(200);
    assertThat(fresh.post("/api/v1/auth/activity", "{}").expect(200).string("$.data.idleMinutes"))
        .isEqualTo("5");
  }
}
