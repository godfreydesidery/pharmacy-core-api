package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Staff;

/**
 * A second person's approval (Batch 4 of the adversarial review; the owner's rules of 23 Nov 2026),
 * shown on vouchers: above the pharmacy's approval limit the API refuses with {@code
 * approval_required}; a manager approves on the same screen with their own password; the request
 * goes through once with the approval, which cannot be used again.
 */
class ApprovalsTest extends IntegrationTest {

  private Api anonymous;
  private Api admin;
  private Api manager;
  private String tenant;
  private String branch;
  private String adminEmail;
  private Staff.Login cashier;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "AP" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    adminEmail = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Approvals %s","legalName":"Approvals %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Neema Owner","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, adminEmail))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(adminEmail, "Admin#2026x")).in(tenant, null);
    branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    Staff.Login asker =
        Staff.login(admin, anonymous, "Branch Manager", "BRANCH_MANAGER", "Manager#2026x");
    manager = anonymous.as(anonymous.signIn(asker.signIn(), "Manager#2026x")).in(tenant, branch);
    cashier = Staff.login(admin, anonymous, "Till Cashier", "CASHIER", "Cashier#2026x");
  }

  @Test
  @DisplayName("the approval limit is a setting: 50,000 by default, changeable per pharmacy")
  void limitIsASetting() {
    Response settings = admin.get("/api/v1/settings").expect(200);
    assertThat(settings.read("$.data.approvalLimit", Double.class)).isEqualTo(50_000.0);
    assertThat(settings.read("$.data.returnWindowDays", Integer.class)).isEqualTo(7);
    // On for new pharmacies in production; the test profile turns the default off
    // (pharmacy.tenancy.require-open-shift-by-default) so tests sell at once.
    assertThat(settings.read("$.data.requireOpenShift", Boolean.class)).isFalse();
    admin.put("/api/v1/settings", "{\"approvalLimit\":20000,\"returnWindowDays\":14}").expect(200);
    Response changed = admin.get("/api/v1/settings").expect(200);
    assertThat(changed.read("$.data.approvalLimit", Double.class)).isEqualTo(20_000.0);
    assertThat(changed.read("$.data.returnWindowDays", Integer.class)).isEqualTo(14);
  }

  @Test
  @DisplayName("above the limit a voucher needs a manager; the approval works once")
  void voucherAboveTheLimit() {
    // Below the limit: no approval.
    manager.post("/api/v1/vouchers", "{\"value\":10000,\"paidBy\":\"CASH\"}").expect(201);

    String body = "{\"value\":80000,\"paidBy\":\"CASH\",\"holderName\":\"Asha\"}";
    Response refused = manager.post("/api/v1/vouchers", body);
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("VOUCHER");
    assertThat(refused.read("$.amount", Double.class)).isEqualTo(80_000.0);

    // The manager cannot approve their own voucher; a cashier cannot approve; a wrong password.
    Response self =
        manager.post(
            "/api/v1/approvals",
            "{\"action\":\"VOUCHER\",\"amount\":80000,\"login\":\"%s\",\"password\":\"Manager#2026x\"}"
                .formatted(managerSignIn()));
    assertThat(self.expect(422).string("$.reason")).isEqualTo("approver_is_requester");
    Response notApprover =
        manager.post(
            "/api/v1/approvals",
            "{\"action\":\"VOUCHER\",\"amount\":80000,\"login\":\"%s\",\"password\":\"Cashier#2026x\"}"
                .formatted(cashier.signIn()));
    assertThat(notApprover.expect(403).string("$.reason")).isEqualTo("not_an_approver");
    Response wrong =
        manager.post(
            "/api/v1/approvals",
            "{\"action\":\"VOUCHER\",\"amount\":80000,\"login\":\"%s\",\"password\":\"Wrong#2026x\"}"
                .formatted(adminEmail));
    assertThat(wrong.expect(422).string("$.reason")).isEqualTo("invalid_credentials");

    // The owner approves on the manager's screen; the voucher goes through once.
    Response approved =
        manager
            .post(
                "/api/v1/approvals",
                "{\"action\":\"VOUCHER\",\"amount\":80000,\"login\":\"%s\",\"password\":\"Admin#2026x\"}"
                    .formatted(adminEmail))
            .expect(201);
    String token = approved.string("$.data.token");
    assertThat(token).startsWith("pap_");
    assertThat(approved.string("$.data.approvedBy")).isEqualTo("Neema Owner");
    manager.post("/api/v1/vouchers", body, "X-Approval", token).expect(201);
    Response again = manager.post("/api/v1/vouchers", body, "X-Approval", token);
    assertThat(again.expect(403).string("$.reason")).isEqualTo("approval_invalid");

    // An approval of a smaller amount does not cover a larger one.
    String small =
        manager
            .post(
                "/api/v1/approvals",
                "{\"action\":\"VOUCHER\",\"amount\":60000,\"login\":\"%s\",\"password\":\"Admin#2026x\"}"
                    .formatted(adminEmail))
            .expect(201)
            .string("$.data.token");
    assertThat(
            manager
                .post("/api/v1/vouchers", body, "X-Approval", small)
                .expect(403)
                .string("$.reason"))
        .isEqualTo("approval_invalid");
  }

  @Test
  @DisplayName("one request may carry several approvals; each action uses its own")
  void severalApprovalsOnOneRequest() {
    String body = "{\"value\":70000,\"paidBy\":\"CASH\"}";
    // An approval of another action does not stand in for this one: it is asked for.
    String other =
        manager
            .post(
                "/api/v1/approvals",
                "{\"action\":\"REFUND\",\"amount\":70000,\"login\":\"%s\",\"password\":\"Admin#2026x\"}"
                    .formatted(adminEmail))
            .expect(201)
            .string("$.data.token");
    Response asked = manager.post("/api/v1/vouchers", body, "X-Approval", other);
    assertThat(asked.expect(403).string("$.reason")).isEqualTo("approval_required");
    assertThat(asked.string("$.action")).isEqualTo("VOUCHER");

    String voucher =
        manager
            .post(
                "/api/v1/approvals",
                "{\"action\":\"VOUCHER\",\"amount\":70000,\"login\":\"%s\",\"password\":\"Admin#2026x\"}"
                    .formatted(adminEmail))
            .expect(201)
            .string("$.data.token");
    // A list header (RFC 9110): the voucher uses its approval; the refund's stays unused.
    manager.post("/api/v1/vouchers", body, "X-Approval", other + ", " + voucher).expect(201);
    // The voucher's approval is spent; the refund's was not, and still does not cover a voucher.
    Response spent = manager.post("/api/v1/vouchers", body, "X-Approval", voucher);
    assertThat(spent.expect(403).string("$.reason")).isEqualTo("approval_invalid");
  }

  private String managerSignIn() {
    return manager.get("/api/v1/auth/me").expect(200).string("$.data.user.email");
  }
}
