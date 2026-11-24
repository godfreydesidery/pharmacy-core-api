package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Staff;

/**
 * The owner's decision of 24 Nov 2026, "owner may self-approve if alone": the pharmacy's owner,
 * with nobody else who could approve at that branch, approves their own request after typing their
 * password. It is marked as a self-approval (on the approval, in the audit trail) and ends as soon
 * as someone else could approve. Nobody else ever approves their own request.
 */
class SelfApprovalTest extends IntegrationTest {

  private static final String VOUCHER =
      "{\"value\":80000,\"paidBy\":\"CASH\",\"holderName\":\"Asha\"}";

  private Api anonymous;
  private Api owner;
  private String ownerEmail;
  private String tenant;
  private String branch;

  @BeforeEach
  void ownerAlone() {
    anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SA" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    ownerEmail = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Alone %s","legalName":"Alone %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Juma Owner","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, ownerEmail))
            .expect(201)
            .string("$.data.uid");
    Api signedIn = anonymous.as(anonymous.signIn(ownerEmail, "Admin#2026x-strong"));
    branch =
        signedIn
            .in(tenant, null)
            .get("/api/v1/auth/me")
            .expect(200)
            .string("$.data.memberships[0].branches[0].uid");
    owner = signedIn.in(tenant, branch);
  }

  private Response approve(Api asker, String login, String password) {
    return asker.post(
        "/api/v1/approvals",
        "{\"action\":\"VOUCHER\",\"amount\":80000,\"login\":\"%s\",\"password\":\"%s\"}"
            .formatted(login, password));
  }

  @Test
  @DisplayName("the owner alone approves their own request, marked; a second approver ends it")
  void ownerAloneSelfApproves() {
    Response refused = owner.post("/api/v1/vouchers", VOUCHER).expect(403);
    assertThat(refused.string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.read("$.selfApproval", Boolean.class)).isTrue();

    // A wrong password still counts as one.
    assertThat(approve(owner, ownerEmail, "Wrong#2026x").expect(422).string("$.reason"))
        .isEqualTo("invalid_credentials");
    Response self = approve(owner, ownerEmail, "Admin#2026x-strong").expect(201);
    assertThat(self.read("$.data.selfApproved", Boolean.class)).isTrue();
    assertThat(self.string("$.message")).startsWith("Self-approved by");
    owner.post("/api/v1/vouchers", VOUCHER, "X-Approval", self.string("$.data.token")).expect(201);
    List<String> actions =
        owner
            .get("/api/v1/audit?entityType=Approval&limit=50")
            .expect(200)
            .json()
            .read("$.data.items[*].action");
    assertThat(actions).contains("APPROVAL_SELF_GRANTED");

    // A cashier cannot approve, so the owner is still alone.
    Staff.login(
        owner.in(tenant, null), anonymous, "Till Cashier", "CASHIER", "Cashier#2026x-strong");
    assertThat(owner.post("/api/v1/vouchers", VOUCHER).read("$.selfApproval", Boolean.class))
        .isTrue();

    // A branch manager can: from now on the owner needs them, and nobody approves themselves.
    Staff.Login managerLogin =
        Staff.login(
            owner.in(tenant, null),
            anonymous,
            "Branch Manager",
            "BRANCH_MANAGER",
            "Manager#2026x-strong");
    Response again = owner.post("/api/v1/vouchers", VOUCHER).expect(403);
    assertThat(again.read("$.selfApproval", Boolean.class)).isFalse();
    assertThat(approve(owner, ownerEmail, "Admin#2026x-strong").expect(422).string("$.reason"))
        .isEqualTo("approver_is_requester");
    Response byManager = approve(owner, managerLogin.signIn(), "Manager#2026x-strong").expect(201);
    assertThat(byManager.read("$.data.selfApproved", Boolean.class)).isFalse();

    // A manager (not the owner) never approves their own request.
    Api manager =
        anonymous
            .as(anonymous.signIn(managerLogin.signIn(), "Manager#2026x-strong"))
            .in(tenant, branch);
    assertThat(manager.post("/api/v1/vouchers", VOUCHER).read("$.selfApproval", Boolean.class))
        .isFalse();
    assertThat(
            approve(manager, managerLogin.signIn(), "Manager#2026x-strong")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("approver_is_requester");
  }

  @Test
  @DisplayName("a self-approved destruction still names a witness other than the owner")
  void selfApprovedDestructionNeedsAWitness() {
    owner.in(tenant, null).put("/api/v1/settings", "{\"approvalLimit\":0}").expect(200);
    String product =
        owner
            .in(tenant, null)
            .post(
                "/api/v1/products",
                """
                {"name":"ORS Sachet","genericName":"Oral rehydration salts","dosageForm":"POWDER",
                 "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
                 "sellingPrice":800,"costPrice":300,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
    String batch =
        owner
            .post(
                "/api/v1/stock/opening",
                """
                {"productUid":"%s","batchNumber":"B-1","expiryDate":"%s","quantity":10,"unitCost":300}
                """
                    .formatted(product, LocalDate.now().plusYears(2)))
            .expect(201)
            .string("$.data.batchUid");
    String destruction =
        """
        {"method":"Incineration","witnessName":%s,"note":"Crushed",
         "lines":[{"batchUid":"%s","quantity":1,"reason":"DAMAGED"}]}
        """;
    Response refused =
        owner.post("/api/v1/destructions", destruction.formatted("null", batch)).expect(403);
    assertThat(refused.read("$.selfApproval", Boolean.class)).isTrue();
    String token =
        owner
            .post(
                "/api/v1/approvals",
                "{\"action\":\"%s\",\"amount\":300,\"login\":\"%s\",\"password\":\"Admin#2026x-strong\"}"
                    .formatted(refused.string("$.action"), ownerEmail))
            .expect(201)
            .string("$.data.token");
    // The owner who approved their own destruction is not its witness: one must be named.
    Response unwitnessed =
        owner.post(
            "/api/v1/destructions", destruction.formatted("null", batch), "X-Approval", token);
    assertThat(unwitnessed.status()).isEqualTo(422);
    assertThat(unwitnessed.string("$.field")).isEqualTo("witnessName");
    String again =
        owner
            .post(
                "/api/v1/approvals",
                "{\"action\":\"%s\",\"amount\":300,\"login\":\"%s\",\"password\":\"Admin#2026x-strong\"}"
                    .formatted(refused.string("$.action"), ownerEmail))
            .expect(201)
            .string("$.data.token");
    Response destroyed =
        owner
            .post(
                "/api/v1/destructions",
                destruction.formatted("\"Asha (TMDA)\"", batch),
                "X-Approval",
                again)
            .expect(201);
    assertThat(destroyed.read("$.data.witnessApproved", Boolean.class)).isFalse();
    assertThat(destroyed.string("$.data.typedWitnessName")).isEqualTo("Asha (TMDA)");
  }
}
