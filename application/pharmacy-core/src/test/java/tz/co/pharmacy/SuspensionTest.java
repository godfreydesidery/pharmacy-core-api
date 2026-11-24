package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Suspension kinds: a dispute (legal hold) blocks everyone, admins included; a manual reactivation
 * of an unpaid pharmacy puts its subscription back in the lifecycle.
 */
class SuspensionTest extends IntegrationTest {

  private Api root;
  private Api admin;
  private String tenant;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SU" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Susp %s","legalName":"Susp %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
  }

  @Test
  @DisplayName("a dispute is a legal hold: nobody works in the pharmacy, its admin included")
  void dispute() {
    Api.Response held =
        root.post(
                "/api/v1/platform/tenants/" + tenant + "/suspend",
                "{\"kind\":\"DISPUTE\",\"reason\":\"Court order 123/2026, Kisutu\"}")
            .expect(200);
    assertThat(held.string("$.data.suspensionKind")).isEqualTo("DISPUTE");
    assertThat(admin.get("/api/v1/customers").expect(403).string("$.reason"))
        .isEqualTo("tenant_suspended");
    // The root administrator can still look, for the dispute.
    root.in(tenant, null).get("/api/v1/customers").expect(200);
    root.post("/api/v1/platform/tenants/" + tenant + "/reactivate", "{}").expect(200);
    admin.get("/api/v1/customers").expect(200);
    // A suspension needs a kind the platform knows and a reason.
    root.post(
            "/api/v1/platform/tenants/" + tenant + "/suspend",
            "{\"kind\":\"WHIM\",\"reason\":\"x\"}")
        .expect(422);
    root.post("/api/v1/platform/tenants/" + tenant + "/suspend", "{\"kind\":\"ABUSE\"}")
        .expect(422);
  }

  @Test
  @DisplayName("reactivating an unpaid pharmacy by hand keeps its subscription in the lifecycle")
  void manualReactivationResumesBilling() {
    LocalDate trialEnds =
        LocalDate.parse(
            root.get("/api/v1/platform/tenants/" + tenant + "/subscription")
                .expect(200)
                .string("$.data.trialEnds"));
    run(trialEnds.minusDays(7));
    run(trialEnds.plusDays(1));
    run(trialEnds.plusDays(8));
    assertThat(
            root.get("/api/v1/platform/tenants/" + tenant + "/subscription")
                .expect(200)
                .string("$.data.status"))
        .isEqualTo("SUSPENDED");

    root.post("/api/v1/platform/tenants/" + tenant + "/reactivate", "{}").expect(200);
    Api.Response sub = root.get("/api/v1/platform/tenants/" + tenant + "/subscription").expect(200);
    // Still owing: past due again, with a fresh grace period, so the lifecycle goes on.
    assertThat(sub.string("$.data.status")).isEqualTo("PAST_DUE");
    assertThat(sub.string("$.data.graceEnds")).isNotNull();
    admin.post("/api/v1/customers", "{\"name\":\"Back\",\"phone\":\"+255700000010\"}").expect(201);
  }

  private void run(LocalDate asOf) {
    root.post(
            "/api/v1/platform/subscriptions/run",
            "{\"asOf\":\"%s\",\"tenantUid\":\"%s\"}".formatted(asOf, tenant))
        .expect(200);
  }
}
