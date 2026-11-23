package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
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
 * The owner's role presets and finance controls (Batch 4, decisions of 23 Nov 2026): new pharmacies
 * get presets that keep selling apart from refunds, stock and the catalogue; an existing pharmacy's
 * admin applies the recommended roles or keeps their own, once; the period lock has its own
 * permission and moving it back needs a second person (FRD-10); credit limits are a finance
 * decision and write-offs always need approval (FRD-17); a supplier return without a goods receipt
 * needs a purchasing manager (FRD-16).
 */
class ControlsFinanceTest extends IntegrationTest {

  private Api anonymous;
  private Api admin;
  private Api hq;
  private String tenant;
  private String branch;
  private String adminEmail;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "CF" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    adminEmail = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Controls %s","legalName":"Controls %s Ltd","country":"TZ",
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
    hq = admin.in(tenant, branch);
  }

  // ------------------------------------------------------------------ presets

  @Test
  @DisplayName("a new pharmacy gets the split presets and no notice")
  void newPharmacyPresets() {
    assertThat(permissions("PHARMACIST"))
        .doesNotContain("sales.refund", "inventory.adjust", "catalogue.manage")
        .contains("sales.sell", "partners.manage");
    assertThat(permissions("BRANCH_MANAGER"))
        .doesNotContain("finance.period_lock", "users.manage")
        .contains("finance.manage", "approvals.grant");
    assertThat(permissions("ADMIN")).contains("finance.period_lock", "approvals.grant");
    assertThat(permissions("ACCOUNTANT")).contains("finance.period_lock");
    assertThat(admin.get("/api/v1/permissions").expect(200).body()).contains("finance.period_lock");

    Response notice = admin.get("/api/v1/roles/recommendations").expect(200);
    assertThat(notice.read("$.data.showNotice", Boolean.class)).isFalse();
    assertThat(notice.read("$.data.dismissed", Boolean.class)).isFalse();
    assertThat(notice.json().read("$.data.roles", List.class)).isEmpty();
    assertThat(notice.string("$.data.presetsVersion")).isEqualTo("2026-11-23");
  }

  @Test
  @DisplayName("an existing pharmacy sees what differs, applies the recommended roles, once")
  void recommendationsApplied() {
    simulateOldPresets();
    Staff.Login cashier = Staff.login(admin, anonymous, "Cashier", "CASHIER", "Cashier#2026x");
    Api till = anonymous.as(anonymous.signIn(cashier.signIn(), "Cashier#2026x")).in(tenant, null);
    assertThat(till.get("/api/v1/roles/recommendations").expect(403).string("$.reason"))
        .isEqualTo("forbidden");

    Response notice = admin.get("/api/v1/roles/recommendations").expect(200);
    assertThat(notice.read("$.data.showNotice", Boolean.class)).isTrue();
    assertThat(notice.json().read("$.data.roles[*].code", List.class))
        .containsExactly("BRANCH_MANAGER", "PHARMACIST");
    assertThat(notice.json().read("$.data.roles[0].remove", List.class))
        .containsExactly("finance.period_lock");
    assertThat(notice.json().read("$.data.roles[0].add", List.class)).isEmpty();
    assertThat(notice.json().read("$.data.roles[1].remove", List.class))
        .containsExactly("sales.refund", "inventory.adjust", "catalogue.manage");
    assertThat(notice.string("$.data.roles[1].roleUid")).isEqualTo(roleUid("PHARMACIST"));
    assertThat(notice.string("$.data.roles[1].name")).isEqualTo("Pharmacist");

    Response applied = admin.post("/api/v1/roles/recommendations/apply", null).expect(200);
    assertThat(applied.json().read("$.data.roles", List.class)).isEmpty();
    assertThat(applied.read("$.data.showNotice", Boolean.class)).isFalse();
    assertThat(applied.string("$.data.decision")).isEqualTo("APPLIED");
    assertThat(permissions("PHARMACIST"))
        .doesNotContain("sales.refund", "inventory.adjust", "catalogue.manage");
    assertThat(permissions("BRANCH_MANAGER")).doesNotContain("finance.period_lock");
    assertThat(permissions("ADMIN")).contains("finance.period_lock", "users.manage");

    // Each change is in the audit trail with its before and after.
    Api.eventually(
        () ->
            admin
                .get("/api/v1/audit?action=ROLE_PERMISSIONS_CHANGED&limit=50")
                .expect(200)
                .body()
                .contains("Recommended roles applied"));
  }

  @Test
  @DisplayName("an admin who keeps their roles is not asked again")
  void recommendationsDismissed() {
    simulateOldPresets();
    Response dismissed = admin.post("/api/v1/roles/recommendations/dismiss", null).expect(200);
    assertThat(dismissed.read("$.data.dismissed", Boolean.class)).isTrue();
    assertThat(dismissed.read("$.data.showNotice", Boolean.class)).isFalse();
    assertThat(dismissed.string("$.data.decidedBy")).isNotBlank();
    Response again = admin.get("/api/v1/roles/recommendations").expect(200);
    assertThat(again.read("$.data.dismissed", Boolean.class)).isTrue();
    assertThat(again.read("$.data.showNotice", Boolean.class)).isFalse();
    // The difference stays visible in the roles screen, and nothing was taken away.
    assertThat(again.json().read("$.data.roles", List.class)).hasSize(2);
    assertThat(permissions("PHARMACIST")).contains("sales.refund", "inventory.adjust");
    // Dismissing twice (two tabs, two instances) is harmless.
    admin.post("/api/v1/roles/recommendations/dismiss", null).expect(200);
  }

  @Test
  @DisplayName("applying never leaves nobody who can manage people")
  void applyKeepsAPeopleManager() {
    // The branch manager was given "manage users" and the owner's own membership was switched off.
    List<String> manager = permissions("BRANCH_MANAGER");
    manager.add("users.manage");
    changeRole("BRANCH_MANAGER", manager);
    Staff.Login login = Staff.login(admin, anonymous, "Manager", "BRANCH_MANAGER", "Manager#2026x");
    Api boss = anonymous.as(anonymous.signIn(login.signIn(), "Manager#2026x")).in(tenant, null);
    String owner =
        admin
            .get("/api/v1/users?q=" + adminEmail)
            .expect(200)
            .string("$.data.items[0].membershipUid");
    boss.put(
            "/api/v1/users/" + owner,
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"active\":false}"
                .formatted(roleUid("ADMIN")))
        .expect(200);

    Response refused = boss.post("/api/v1/roles/recommendations/apply", null).expect(409);
    assertThat(refused.string("$.reason")).isEqualTo("last_admin");
    assertThat(
            boss.get("/api/v1/roles")
                .expect(200)
                .json()
                .read("$.data[?(@.code == 'BRANCH_MANAGER')].permissions[*]", List.class))
        .contains("users.manage");
  }

  // ------------------------------------------------------------------ period lock

  @Test
  @DisplayName("the period lock: own permission, forward freely, back only with an approval")
  void periodLock() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Staff.Login manager =
        Staff.login(admin, anonymous, "Manager", "BRANCH_MANAGER", "Manager#2026x");
    Api branchManager =
        anonymous.as(anonymous.signIn(manager.signIn(), "Manager#2026x")).in(tenant, null);
    Staff.Login accountant =
        Staff.login(admin, anonymous, "Accountant", "ACCOUNTANT", "Account#2026x");
    Api books =
        anonymous.as(anonymous.signIn(accountant.signIn(), "Account#2026x")).in(tenant, null);

    // finance.manage alone (the branch manager) no longer moves the lock.
    Response refused = branchManager.put("/api/v1/finance/period-lock", lock(today.minusDays(10)));
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("forbidden");

    // The accountant closes and moves the lock forward with no approval.
    books.put("/api/v1/finance/period-lock", lock(today.minusDays(10))).expect(200);
    books.put("/api/v1/finance/period-lock", lock(today.minusDays(5))).expect(200);
    // The same date again changes nothing.
    books.put("/api/v1/finance/period-lock", lock(today.minusDays(5))).expect(200);

    // Back to an earlier date: a second person approves; the approval works once.
    Response back = books.put("/api/v1/finance/period-lock", lock(today.minusDays(8)));
    assertThat(back.expect(403).string("$.reason")).isEqualTo("approval_required");
    assertThat(back.string("$.action")).isEqualTo("PERIOD_UNLOCK");
    String token = approve(books, "PERIOD_UNLOCK", 0, manager.signIn(), "Manager#2026x");
    Response moved =
        books
            .put("/api/v1/finance/period-lock", lock(today.minusDays(8)), "X-Approval", token)
            .expect(200);
    assertThat(moved.string("$.data.lockedThrough")).isEqualTo(today.minusDays(8).toString());
    assertThat(
            books
                .put("/api/v1/finance/period-lock", "{\"lockedThrough\":null}", "X-Approval", token)
                .expect(403)
                .string("$.reason"))
        .isEqualTo("approval_invalid");

    // Removing the lock is unlocking too.
    assertThat(
            books
                .put("/api/v1/finance/period-lock", "{\"lockedThrough\":null}")
                .expect(403)
                .string("$.reason"))
        .isEqualTo("approval_required");
    String again = approve(books, "PERIOD_UNLOCK", 0, adminEmail, "Admin#2026x");
    books
        .put("/api/v1/finance/period-lock", "{\"lockedThrough\":null}", "X-Approval", again)
        .expect(200);
    assertThat(admin.get("/api/v1/finance/period-lock").expect(200).string("$.data.lockedThrough"))
        .isNull();

    // Every change is audited with its before and after.
    Api.eventually(
        () -> {
          String audit =
              admin.get("/api/v1/audit?action=PERIOD_LOCK_CHANGED&limit=50").expect(200).body();
          return audit.contains(today.minusDays(5).toString())
              && audit.contains(today.minusDays(8).toString());
        });
  }

  // ------------------------------------------------------------------ credit

  @Test
  @DisplayName("customers: partners.manage edits them, only finance sets their credit limit")
  void creditLimitIsFinance() {
    Staff.Login login = Staff.login(admin, anonymous, "Pharmacist", "PHARMACIST", "Pharma#2026x");
    Api pharmacist =
        anonymous.as(anonymous.signIn(login.signIn(), "Pharma#2026x")).in(tenant, null);

    String walkIn =
        pharmacist
            .post("/api/v1/customers", "{\"name\":\"Walk-in Asha\",\"creditLimit\":0}")
            .expect(201)
            .string("$.data.uid");
    Response refused =
        pharmacist.post("/api/v1/customers", "{\"name\":\"Clinic\",\"creditLimit\":50000}");
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("credit_limit_needs_finance");

    // Finance sets the limit; the pharmacist may still edit the rest, the limit stays.
    admin
        .put("/api/v1/customers/" + walkIn, "{\"name\":\"Asha\",\"creditLimit\":100000}")
        .expect(200);
    Response renamed =
        pharmacist
            .put(
                "/api/v1/customers/" + walkIn,
                "{\"name\":\"Asha Juma\",\"phone\":\"+255700000009\"}")
            .expect(200);
    assertThat(renamed.read("$.data.creditLimit", Double.class)).isEqualTo(100_000.0);
    pharmacist
        .put("/api/v1/customers/" + walkIn, "{\"name\":\"Asha Juma\",\"creditLimit\":100000}")
        .expect(200);
    for (String limit : List.of("200000", "0")) {
      assertThat(
              pharmacist
                  .put(
                      "/api/v1/customers/" + walkIn,
                      "{\"name\":\"Asha Juma\",\"creditLimit\":%s}".formatted(limit))
                  .expect(403)
                  .string("$.reason"))
          .isEqualTo("credit_limit_needs_finance");
    }
    // An omitted limit is kept for finance too.
    Response kept = admin.put("/api/v1/customers/" + walkIn, "{\"name\":\"Asha J.\"}").expect(200);
    assertThat(kept.read("$.data.creditLimit", Double.class)).isEqualTo(100_000.0);
  }

  @Test
  @DisplayName("writing off a customer's debt always needs a second person's approval")
  void writeOffNeedsApproval() {
    Staff.Login manager =
        Staff.login(admin, anonymous, "Manager", "BRANCH_MANAGER", "Manager#2026x");
    String supplier = supplier();
    String product = product("Amoxicillin 500mg");
    receipt(supplier, product, "AMX-1");
    String clinic =
        admin
            .post("/api/v1/customers", "{\"name\":\"City Clinic\",\"creditLimit\":50000}")
            .expect(201)
            .string("$.data.uid");
    hq.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","customerUid":"%s","lines":[{"productUid":"%s","quantity":2}],
             "payments":[{"method":"CREDIT","amount":2000}]}
            """
                .formatted(UUID.randomUUID(), clinic, product))
        .expect(201);
    String body = "{\"customerUid\":\"%s\",\"amount\":1500,\"reason\":\"Clinic closed\"}";

    Response refused = admin.post("/api/v1/receivables/write-offs", body.formatted(clinic));
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("CREDIT_WRITE_OFF");
    assertThat(refused.read("$.amount", Double.class)).isEqualTo(1_500.0);

    String small = approve(admin, "CREDIT_WRITE_OFF", 500, manager.signIn(), "Manager#2026x");
    assertThat(
            admin
                .post(
                    "/api/v1/receivables/write-offs",
                    body.formatted(clinic),
                    "X-Approval",
                    small,
                    "Idempotency-Key",
                    UUID.randomUUID().toString())
                .expect(403)
                .string("$.reason"))
        .isEqualTo("approval_invalid");
    String token = approve(admin, "CREDIT_WRITE_OFF", 1500, manager.signIn(), "Manager#2026x");
    admin
        .post(
            "/api/v1/receivables/write-offs",
            body.formatted(clinic),
            "X-Approval",
            token,
            "Idempotency-Key",
            UUID.randomUUID().toString())
        .expect(201);
    assertThat(
            admin
                .get("/api/v1/receivables/accounts/" + clinic)
                .expect(200)
                .read("$.data.balance", Double.class))
        .isEqualTo(500.0);
  }

  // ------------------------------------------------------------------ supplier returns

  @Test
  @DisplayName("a supplier return without a goods receipt needs purchasing.manage")
  void unlinkedSupplierReturn() {
    String supplier = supplier();
    String product = product("Paracetamol 500mg");
    String receipt = receipt(supplier, product, "PCM-1");
    String batch =
        hq.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .<List<String>>read("$.data[?(@.batchNumber == 'PCM-1')].uid")
            .getFirst();
    String unlinked =
        """
        {"supplierUid":"%s","reason":"Damaged","lines":[{"productUid":"%s","batchUid":"%s","quantity":1}]}
        """
            .formatted(supplier, product, batch);
    String linked =
        """
        {"supplierUid":"%s","goodsReceiptUid":"%s","reason":"Damaged",
         "lines":[{"productUid":"%s","batchUid":"%s","quantity":1}]}
        """
            .formatted(supplier, receipt, product, batch);

    Staff.Login pharmacistLogin =
        Staff.login(admin, anonymous, "Pharmacist", "PHARMACIST", "Pharma#2026x");
    Api pharmacist =
        anonymous.as(anonymous.signIn(pharmacistLogin.signIn(), "Pharma#2026x")).in(tenant, branch);
    Response refused = pharmacist.post("/api/v1/supplier-returns", unlinked);
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("supplier_return_needs_receipt");
    pharmacist.post("/api/v1/supplier-returns", linked).expect(201);

    Staff.Login storeLogin = Staff.login(admin, anonymous, "Store", "STORE_KEEPER", "Store#2026xy");
    Api store =
        anonymous.as(anonymous.signIn(storeLogin.signIn(), "Store#2026xy")).in(tenant, branch);
    store.post("/api/v1/supplier-returns", unlinked).expect(201);
  }

  // ------------------------------------------------------------------ helpers

  /** Gives the built-in roles their permissions as an older pharmacy had them. */
  private void simulateOldPresets() {
    List<String> pharmacist = permissions("PHARMACIST");
    pharmacist.addAll(List.of("sales.refund", "inventory.adjust", "catalogue.manage"));
    changeRole("PHARMACIST", pharmacist);
    List<String> manager = permissions("BRANCH_MANAGER");
    manager.add("finance.period_lock");
    changeRole("BRANCH_MANAGER", manager);
  }

  private void changeRole(String code, List<String> permissions) {
    String list = String.join(",", permissions.stream().map(p -> "\"" + p + "\"").toList());
    admin
        .put(
            "/api/v1/roles/" + roleUid(code),
            "{\"code\":\"%s\",\"name\":\"x\",\"permissions\":[%s]}".formatted(code, list))
        .expect(200);
  }

  private List<String> permissions(String code) {
    List<String> found =
        admin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + code + "')].permissions[*]", List.class);
    return new java.util.ArrayList<>(found);
  }

  private String roleUid(String code) {
    List<String> uids =
        admin
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + code + "')].uid", List.class);
    return uids.getFirst();
  }

  private static String lock(LocalDate through) {
    return "{\"lockedThrough\":\"%s\"}".formatted(through);
  }

  private static String approve(
      Api requester, String action, double amount, String login, String password) {
    return requester
        .post(
            "/api/v1/approvals",
            "{\"action\":\"%s\",\"amount\":%s,\"login\":\"%s\",\"password\":\"%s\"}"
                .formatted(action, amount, login, password))
        .expect(201)
        .string("$.data.token");
  }

  private String supplier() {
    return admin
        .post(
            "/api/v1/suppliers",
            "{\"name\":\"Supplier %s\",\"paymentTerms\":\"NET_30\"}"
                .formatted(UUID.randomUUID().toString().substring(0, 6)))
        .expect(201)
        .string("$.data.uid");
  }

  private String product(String name) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
             "sellingPrice":1000,"costPrice":400,"taxRatePercent":0}
            """
                .formatted(name, name))
        .expect(201)
        .string("$.data.uid");
  }

  private String receipt(String supplier, String product, String batch) {
    return hq.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"%s","lines":[
              {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":20,"unitCost":400}]}
            """
                .formatted(
                    supplier,
                    "INV-" + UUID.randomUUID().toString().substring(0, 6),
                    product,
                    batch,
                    LocalDate.now().plusYears(1)))
        .expect(201)
        .string("$.data.uid");
  }
}
