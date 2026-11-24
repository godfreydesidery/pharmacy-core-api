package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Roots;

/**
 * FRD-18 (owner's decision of 24 Nov 2026): lifting a legal hold and erasing a pharmacy need two
 * root administrators. One asks; a different one approves with their password within 24 hours; the
 * action then runs once; it is audited; with a single root administrator both are refused.
 */
class RootApprovalsTest extends IntegrationTest {

  private static final String ROOT = "root@test.local";

  @Autowired DataSource dataSource;

  private Api anonymous;
  private Api root;
  private String tenant;

  @BeforeEach
  void pharmacyUnderHold() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn(ROOT, "Root#Test2026"));
    String code = "RA" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Held %s","legalName":"Held %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s@test.local","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, code.toLowerCase()))
            .expect(201)
            .string("$.data.uid");
    root.post("/api/v1/platform/tenants/" + tenant + "/legal-hold", "{\"reason\":\"Case 4/2026\"}")
        .expect(200);
  }

  @Test
  @DisplayName("one root administrator asks, another approves with their password, once")
  void twoPeople() {
    Api second = Roots.second(root, anonymous);
    // The single-person route is closed.
    Response alone = root.delete("/api/v1/platform/tenants/" + tenant + "/legal-hold").expect(403);
    assertThat(alone.string("$.reason")).isEqualTo("root_approval_required");
    assertThat(alone.string("$.action")).isEqualTo("LIFT_LEGAL_HOLD");

    Response asked = ask("LIFT_LEGAL_HOLD", "{}").expect(201);
    String uid = asked.string("$.data.uid");
    assertThat(asked.string("$.data.status")).isEqualTo("PENDING");
    assertThat(asked.string("$.data.expiresAt")).isNotNull();
    assertThat(ask("LIFT_LEGAL_HOLD", "{}").expect(409).string("$.reason"))
        .isEqualTo("root_approval_pending");
    List<String> pending =
        second.get("/api/v1/platform/approvals").expect(200).json().read("$.data[*].uid");
    assertThat(pending).contains(uid);

    // Never the person who asked.
    assertThat(approve(root, uid, "Root#Test2026").expect(403).string("$.reason"))
        .isEqualTo("approver_is_requester");
    // The approver's own password, checked like a sign-in.
    assertThat(approve(second, uid, "Wrong#password-1").expect(422).string("$.reason"))
        .isEqualTo("invalid_password");
    assertThat(
            root.get("/api/v1/platform/tenants/" + tenant).read("$.data.legalHold", Boolean.class))
        .isTrue();

    Response approved = approve(second, uid, Roots.SECOND_PASSWORD).expect(200);
    assertThat(approved.string("$.data.status")).isEqualTo("EXECUTED");
    assertThat(approved.string("$.data.approvedByName")).isEqualTo("Second Root");
    assertThat(approved.read("$.data.tenant.legalHold", Boolean.class)).isFalse();
    // Single use.
    assertThat(approve(second, uid, Roots.SECOND_PASSWORD).expect(409).string("$.reason"))
        .isEqualTo("root_approval_closed");

    // Audited at platform level: asked and approved.
    List<String> actions =
        new JdbcTemplate(dataSource)
            .queryForList(
                "select action from audit.entries where entity_uid = ?::uuid"
                    + " and action like 'ROOT_APPROVAL_%' order by at",
                String.class, tenant);
    assertThat(actions).containsExactly("ROOT_APPROVAL_REQUESTED", "ROOT_APPROVAL_APPROVED");
  }

  @Test
  @DisplayName("a request waits 24 hours at most; a pending one can be cancelled")
  void expiryAndCancel() {
    Api second = Roots.second(root, anonymous);
    String uid = ask("LIFT_LEGAL_HOLD", "{}").expect(201).string("$.data.uid");
    new JdbcTemplate(dataSource)
        .update(
            "update platform.root_approvals set requested_at = now() - interval '2 days',"
                + " expires_at = now() - interval '1 day' where uid = ?::uuid",
            uid);
    assertThat(approve(second, uid, Roots.SECOND_PASSWORD).expect(409).string("$.reason"))
        .isEqualTo("root_approval_expired");
    // Asking again is possible; the old one shows as expired.
    String again = ask("LIFT_LEGAL_HOLD", "{}").expect(201).string("$.data.uid");
    List<String> statuses =
        root.get("/api/v1/platform/approvals?status=ALL")
            .expect(200)
            .json()
            .read("$.data[?(@.uid == '" + uid + "')].status");
    assertThat(statuses).containsExactly("EXPIRED");
    Response cancelled =
        second.post("/api/v1/platform/approvals/" + again + "/cancel", "{}").expect(200);
    assertThat(cancelled.string("$.data.status")).isEqualTo("CANCELLED");
    assertThat(approve(second, again, Roots.SECOND_PASSWORD).expect(409).string("$.reason"))
        .isEqualTo("root_approval_closed");
  }

  @Test
  @DisplayName("with one active root administrator, both actions are refused")
  void oneRootAlone() {
    Roots.second(root, anonymous);
    List<String> others =
        root.get("/api/v1/platform/root-admins")
            .expect(200)
            .json()
            .read("$.data[?(@.active == true && @.email != '" + ROOT + "')].uid");
    others.forEach(
        uid -> root.post("/api/v1/platform/root-admins/" + uid + "/deactivate", null).expect(200));
    try {
      for (String action : List.of("LIFT_LEGAL_HOLD", "ERASE_TENANT")) {
        assertThat(ask(action, "{}").expect(409).string("$.reason"))
            .as(action)
            .isEqualTo("second_root_admin_required");
      }
      assertThat(
              root.post("/api/v1/platform/tenants/" + tenant + "/erase", "{\"confirmCode\":\"X\"}")
                  .expect(403)
                  .string("$.reason"))
          .isEqualTo("root_approval_required");
    } finally {
      others.forEach(uid -> root.post("/api/v1/platform/root-admins/" + uid + "/reactivate", null));
    }
  }

  private Response ask(String action, String ignored) {
    return root.post(
        "/api/v1/platform/approvals",
        "{\"action\":\"%s\",\"tenantUid\":\"%s\",\"reason\":\"Court order lifted\"}"
            .formatted(action, tenant));
  }

  private static Response approve(Api who, String uid, String password) {
    return who.post(
        "/api/v1/platform/approvals/" + uid + "/approve",
        "{\"password\":\"%s\"}".formatted(password));
  }
}
