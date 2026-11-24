package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Subscription plans: a trial on registration, plan limits and modules enforced at the edge, and
 * the lifecycle invoice → past due → suspended → paid → active again.
 */
class SubscriptionPlansTest extends IntegrationTest {

  @Test
  @DisplayName("trial, limits, gated modules, and the unpaid → suspended → paid lifecycle")
  void lifecycle() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SP" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Plans %s","legalName":"Plans %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);

    // A new pharmacy starts on a trial with every module.
    Response status = admin.get("/api/v1/subscription/status").expect(200);
    assertThat(status.string("$.data.status")).isEqualTo("TRIAL");
    assertThat(status.string("$.data.planCode")).isEqualTo("PRO");
    LocalDate trialEnds = LocalDate.parse(status.string("$.data.trialEnds"));
    assertThat(trialEnds).isEqualTo(LocalDate.now().plusDays(30));
    admin.get("/api/v1/insurance/payers").expect(200);
    // On the trial it takes Kenyan shillings as well.
    admin.put("/api/v1/settings", "{\"acceptedCurrencies\":[\"TZS\",\"KES\"]}").expect(200);

    // Moved to Starter: one branch, no insurance, one currency, no financial reports.
    root.put(
            "/api/v1/platform/tenants/" + tenant + "/subscription",
            "{\"planCode\":\"STARTER\",\"cycle\":\"MONTHLY\"}")
        .expect(200);
    Response branch =
        admin.post("/api/v1/branches", "{\"code\":\"B2\",\"name\":\"Second branch\"}");
    assertThat(branch.expect(422).string("$.reason")).isEqualTo("plan_limit_reached");
    assertThat(admin.get("/api/v1/insurance/payers").expect(403).string("$.reason"))
        .isEqualTo("plan_feature_unavailable");
    Response addUsd =
        admin.put("/api/v1/settings", "{\"acceptedCurrencies\":[\"TZS\",\"KES\",\"USD\"]}");
    assertThat(addUsd.expect(403).string("$.reason")).isEqualTo("plan_feature_unavailable");
    assertThat(addUsd.string("$.field")).isEqualTo("acceptedCurrencies");
    assertThat(addUsd.string("$.detail")).contains("only TZS", "Remove KES, USD");
    // Settings already holding KES still save; removing it does too.
    admin
        .put(
            "/api/v1/settings",
            "{\"receiptFooter\":\"Asante\",\"acceptedCurrencies\":[\"TZS\",\"KES\"]}")
        .expect(200);
    // But the till takes no foreign money on this plan.
    String hq =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    Response kes =
        admin
            .in(tenant, hq)
            .post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
                 "payments":[{"method":"CASH","amount":100,"currency":"KES"}]}
                """
                    .formatted(UUID.randomUUID(), UUID.randomUUID()));
    assertThat(kes.expect(403).string("$.reason")).isEqualTo("plan_feature_unavailable");
    assertThat(kes.string("$.field")).isEqualTo("payments[0].currency");
    admin.put("/api/v1/settings", "{\"acceptedCurrencies\":[\"TZS\"]}").expect(200);
    assertThat(admin.get("/api/v1/reports/trial-balance").expect(403).string("$.reason"))
        .isEqualTo("plan_feature_unavailable");
    // The root administrator is never held back by a plan.
    root.in(tenant, null).get("/api/v1/insurance/payers").expect(200);

    Response usage = admin.get("/api/v1/subscription").expect(200);
    List<Object> branches = usage.json().read("$.data.usage[?(@.limit == 'BRANCHES')].used");
    assertThat(branches).containsExactly(1);

    // A week before the trial ends an invoice is issued (and the admins reminded).
    run(root, trialEnds.minusDays(7), tenant);
    Response sub = root.get("/api/v1/platform/tenants/" + tenant + "/subscription").expect(200);
    assertThat(sub.string("$.data.invoices[0].status")).isEqualTo("OPEN");
    assertThat(sub.read("$.data.invoices[0].amount", Double.class)).isEqualTo(50000.0);
    assertThat(sub.string("$.data.invoices[0].dueDate")).isEqualTo(trialEnds.toString());
    String invoice = sub.string("$.data.invoices[0].uid");

    // Unpaid after the due date: past due, with a grace period; then suspended.
    run(root, trialEnds.plusDays(1), tenant);
    sub = root.get("/api/v1/platform/tenants/" + tenant + "/subscription").expect(200);
    assertThat(sub.string("$.data.status")).isEqualTo("PAST_DUE");
    assertThat(sub.string("$.data.graceEnds")).isEqualTo(trialEnds.plusDays(7).toString());
    admin.get("/api/v1/customers").expect(200);

    run(root, trialEnds.plusDays(8), tenant);
    sub = root.get("/api/v1/platform/tenants/" + tenant + "/subscription").expect(200);
    assertThat(sub.string("$.data.status")).isEqualTo("SUSPENDED");
    Response suspended = root.get("/api/v1/platform/tenants/" + tenant).expect(200);
    assertThat(suspended.string("$.data.status")).isEqualTo("SUSPENDED");
    assertThat(suspended.string("$.data.statusReason")).isEqualTo("Subscription unpaid");
    assertThat(suspended.string("$.data.suspensionKind")).isEqualTo("UNPAID");
    // Unpaid: the admin can still read (to pay and take the data out), not change anything.
    admin.get("/api/v1/customers").expect(200);
    assertThat(
            admin
                .post("/api/v1/customers", "{\"name\":\"Late\",\"phone\":\"+255700000009\"}")
                .expect(403)
                .string("$.reason"))
        .isEqualTo("tenant_suspended_read_only");

    // Paying the invoice reactivates the pharmacy for the invoiced period.
    root.post(
            "/api/v1/platform/subscriptions/invoices/" + invoice + "/payments",
            "{\"method\":\"MOBILE_MONEY\",\"reference\":\"MPESA-SUB-1\"}")
        .expect(200);
    sub = root.get("/api/v1/platform/tenants/" + tenant + "/subscription").expect(200);
    assertThat(sub.string("$.data.status")).isEqualTo("ACTIVE");
    assertThat(sub.string("$.data.periodStart")).isEqualTo(trialEnds.toString());
    assertThat(sub.string("$.data.periodEnd")).isEqualTo(trialEnds.plusMonths(1).toString());
    assertThat(root.get("/api/v1/platform/tenants/" + tenant).expect(200).string("$.data.status"))
        .isEqualTo("ACTIVE");
    admin.get("/api/v1/customers").expect(200);

    Response overview = root.get("/api/v1/platform/subscriptions/overview").expect(200);
    assertThat(overview.read("$.data.active", Integer.class)).isGreaterThanOrEqualTo(1);
  }

  private static void run(Api root, LocalDate asOf, String tenant) {
    root.post(
            "/api/v1/platform/subscriptions/run",
            "{\"asOf\":\"%s\",\"tenantUid\":\"%s\"}".formatted(asOf, tenant))
        .expect(200);
  }
}
