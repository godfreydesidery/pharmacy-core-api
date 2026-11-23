package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Outbox;
import tz.co.pharmacy.support.Staff;

/**
 * The account holes the adversarial review of 23 Nov 2026 found (Batch 2), closed: sensitive
 * account changes need a recently proved password, reset links supersede one another and are
 * capped, common passwords are refused, a role can grant only what its editor holds, the last
 * people-manager stays, account templates are the platform's, paths are read one way only, and the
 * notification stream takes a short ticket instead of the session token.
 */
class AccountHardeningTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  @Autowired DataSource dataSource;

  private Api anonymous;
  private Api root;
  private String tenant;
  private String email;
  private String token;
  private Api admin;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "AH" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    email = "ah." + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Hardened %s","legalName":"Hardened %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Neema Mushi","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    token = anonymous.signIn(email, "Admin#2026x");
    admin = anonymous.as(token).in(tenant, null);
  }

  @Test
  @DisplayName("a sensitive account change needs the password proved in the last few minutes")
  void stepUp() {
    Api me = anonymous.as(token);
    // Just signed in: the change may go ahead.
    me.post("/api/v1/auth/passkeys/register/options", "{}").expect(200);

    // An hour later (an unattended till, a stolen token): it may not.
    new JdbcTemplate(dataSource)
        .update(
            "update access.sessions set verified_at = now() - interval '1 hour'"
                + " where token_hash = encode(sha256(convert_to(?, 'UTF8')), 'hex')",
            token);
    for (String path :
        List.of("/api/v1/auth/passkeys/register/options", "/api/v1/auth/2fa/setup")) {
      assertThat(me.post(path, "{}").expect(403).string("$.reason"))
          .isEqualTo("reauthentication_required");
    }
    assertThat(
            me.post("/api/v1/auth/phone", "{\"phone\":\"+255754000999\"}")
                .expect(403)
                .string("$.reason"))
        .isEqualTo("reauthentication_required");

    // A wrong password does not reopen it; the right one does.
    assertThat(
            me.post("/api/v1/auth/confirm-identity", "{\"password\":\"Wrong#2026x\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_password");
    me.post("/api/v1/auth/confirm-identity", "{\"password\":\"Admin#2026x\"}").expect(200);
    me.post("/api/v1/auth/passkeys/register/options", "{}").expect(200);
  }

  @Test
  @DisplayName("only the newest reset link works, and an account gets at most 3 an hour")
  void resetLinks() {
    Outbox outbox = new Outbox(url, owner, ownerPassword);
    anonymous.post("/api/v1/auth/password-reset", "{\"email\":\"%s\"}".formatted(email));
    String first =
        Outbox.token(outbox.platformMessage("access.password_reset", "EMAIL", email, true));
    anonymous.post("/api/v1/auth/password-reset", "{\"email\":\"%s\"}".formatted(email));
    Api.eventually(
        () -> {
          String body = outbox.platformMessage("access.password_reset", "EMAIL", email, false);
          return body != null && !first.equals(Outbox.token(body));
        });
    String second =
        Outbox.token(outbox.platformMessage("access.password_reset", "EMAIL", email, false));

    Response old =
        anonymous.post(
            "/api/v1/auth/password-reset/complete",
            "{\"token\":\"%s\",\"newPassword\":\"Fresh#2026xyz\"}".formatted(first));
    assertThat(old.expect(422).string("$.reason")).isEqualTo("invalid_token");
    anonymous
        .post(
            "/api/v1/auth/password-reset/complete",
            "{\"token\":\"%s\",\"newPassword\":\"Fresh#2026xyz\"}".formatted(second))
        .expect(200);

    // A third goes out (three this hour); the fourth and fifth are dropped, with the same answer.
    for (int i = 0; i < 2; i++) {
      anonymous.post("/api/v1/auth/password-reset", "{\"email\":\"%s\"}".formatted(email));
    }
    anonymous
        .post("/api/v1/auth/password-reset", "{\"email\":\"%s\"}".formatted(email))
        .expect(202);
    Integer sent =
        new JdbcTemplate(dataSource)
            .queryForObject(
                "select count(*) from access.password_resets r join access.users u"
                    + " on u.uid = r.user_uid where u.email = ?",
                Integer.class,
                email);
    assertThat(sent).isEqualTo(3);
  }

  @Test
  @DisplayName("a common or easily guessed password is refused")
  void commonPasswords() {
    Api me = anonymous.as(token);
    String ownName = email.split("@")[0] + "2026";
    for (String weak : List.of("password123", "12345678", "aaaaaaaaaa", ownName)) {
      Response refused =
          me.post(
              "/api/v1/auth/change-password",
              "{\"currentPassword\":\"Admin#2026x\",\"newPassword\":\"%s\"}".formatted(weak));
      assertThat(refused.expect(422).string("$.reason"))
          .as(weak)
          .isIn("common_password", "weak_password");
    }
  }

  @Test
  @DisplayName("roles grant only what their editor holds; the owner's role and last admin stay")
  void roles() {
    String people =
        admin
            .post(
                "/api/v1/roles",
                """
                {"code":"PEOPLE","name":"People","permissions":["users.manage","dashboard.view"]}
                """)
            .expect(201)
            .string("$.data.uid");
    Staff.Login hr = Staff.login(admin, anonymous, "Hr Officer", "PEOPLE", "People#2026x");
    Api officer = anonymous.as(anonymous.signIn(hr.signIn(), "People#2026x")).in(tenant, null);
    Response escalate =
        officer.post(
            "/api/v1/roles",
            "{\"code\":\"RICH\",\"name\":\"Rich\",\"permissions\":[\"finance.manage\"]}");
    assertThat(escalate.expect(403).string("$.reason")).isEqualTo("permission_not_held");
    Response addToOwn =
        officer.put(
            "/api/v1/roles/" + people,
            "{\"name\":\"People\",\"permissions\":[\"users.manage\",\"settings.manage\"]}");
    assertThat(addToOwn.expect(403).string("$.reason")).isEqualTo("permission_not_held");

    List<String> adminRole =
        admin.get("/api/v1/roles").expect(200).json().read("$.data[?(@.code == 'ADMIN')].uid");
    Response weakenAdmin =
        admin.put(
            "/api/v1/roles/" + adminRole.getFirst(),
            "{\"name\":\"Administrator\",\"permissions\":[\"dashboard.view\"]}");
    assertThat(weakenAdmin.expect(422).string("$.reason")).isEqualTo("admin_role_fixed");
  }

  @Test
  @DisplayName("a pharmacy cannot reword the platform's account messages")
  void accountTemplatesAreFixed() {
    Response refused =
        admin.put(
            "/api/v1/notification-templates/access.invitation/EMAIL/en",
            "{\"subject\":\"Hi\",\"body\":\"Click http://evil.example/{{link}}\"}");
    assertThat(refused.expect(422).string("$.reason")).isEqualTo("template_fixed");
  }

  @Test
  @DisplayName("a path that reads two ways is refused before it reaches a route")
  void strictPaths() {
    assertThat(
            anonymous
                .post("/api/v1/auth;x/password-reset", "{\"email\":\"%s\"}".formatted(email))
                .expect(400)
                .string("$.reason"))
        .isEqualTo("malformed_request");
    anonymous.get("/api/v1//auth/me").expect(400);
  }

  @Test
  @DisplayName("the stream takes a short ticket, never the session token")
  void streamTicket() {
    String ticket =
        anonymous
            .as(token)
            .post("/api/v1/notifications/stream-ticket", null)
            .expect(200)
            .string("$.data.ticket");
    assertThat(ticket).startsWith("pst_").doesNotContain(token);
    anonymous.get("/api/v1/notifications/stream?token=" + token).expect(401);
    assertThat(
            anonymous
                .get("/api/v1/notifications/stream?ticket=pst_forged")
                .expect(401)
                .string("$.reason"))
        .isEqualTo("stream_ticket_expired");
  }
}
