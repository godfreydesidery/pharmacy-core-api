package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/** Reorder suggestions from sales velocity, and closing the books through a date. */
class PlanningTest extends IntegrationTest {

  @Test
  @DisplayName("suggestions cover lead time and cover days, net of stock and open orders")
  void reorderSuggestions() {
    Tenant t = tenant("RO");
    Api admin = t.admin();
    Api hq = t.hq();
    String supplier =
        admin
            .post(
                "/api/v1/suppliers",
                "{\"name\":\"Fast Pharma\",\"paymentTerms\":\"NET_30\",\"leadTimeDays\":5}")
            .expect(201)
            .string("$.data.uid");
    assertThat(
            admin
                .post("/api/v1/suppliers", "{\"name\":\"Slow\",\"leadTimeDays\":400}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_lead_time");

    // Opening stock while setting up, before any trade (FRD-11).
    // Never sold, but below its reorder level of 50 with 5 held, and no supplier yet: 45.
    String gloves = product(admin, "Examination gloves", 50);
    hq.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"G1","expiryDate":"%s","quantity":5,"unitCost":20}
            """
                .formatted(gloves, LocalDate.now().plusYears(2)))
        .expect(201);

    // Sold 30 in the window: 1 a day. Lead time 5 + cover 30 = 35 wanted, 10 held: order 25.
    String amox = product(admin, "Amoxicillin 250mg", 0);
    hq.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"FP-1","lines":[
              {"productUid":"%s","batchNumber":"A1","expiryDate":"%s","quantity":40,"unitCost":100}]}
            """
                .formatted(supplier, amox, LocalDate.now().plusYears(1)))
        .expect(201);
    hq.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":30}],
             "payments":[{"method":"CASH","amount":9000}]}
            """
                .formatted(UUID.randomUUID(), amox))
        .expect(201);

    Response plan = hq.get("/api/v1/reorder-suggestions").expect(200);
    assertThat(plan.read("$.data.windowDays", Integer.class)).isEqualTo(30);
    assertThat(plan.string("$.data.groups[0].supplierUid")).isEqualTo(supplier);
    assertThat(plan.read("$.data.groups[0].leadTimeDays", Integer.class)).isEqualTo(5);
    assertThat(plan.string("$.data.groups[0].lines[0].productUid")).isEqualTo(amox);
    assertThat(plan.read("$.data.groups[0].lines[0].onHand", Integer.class)).isEqualTo(10);
    assertThat(plan.read("$.data.groups[0].lines[0].suggested", Integer.class)).isEqualTo(25);
    assertThat(plan.read("$.data.groups[0].lines[0].unitCost", Double.class)).isEqualTo(100.0);
    assertThat(plan.read("$.data.groups[1].supplierUid", String.class)).isNull();
    assertThat(plan.read("$.data.groups[1].lines[0].suggested", Integer.class)).isEqualTo(45);

    // A shorter cover asks for less: 1 × (5 + 10) − 10 = 5.
    assertThat(
            hq.get("/api/v1/reorder-suggestions?coverDays=10")
                .expect(200)
                .read("$.data.groups[0].lines[0].suggested", Integer.class))
        .isEqualTo(5);
    hq.get("/api/v1/reorder-suggestions?windowDays=0").expect(422);

    // Products without a supplier need one chosen first.
    assertThat(
            hq.post(
                    "/api/v1/reorder-suggestions/orders",
                    """
                    {"orders":[{"lines":[{"productUid":"%s","quantity":45,"unitCost":20}]}]}
                    """
                        .formatted(gloves))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("supplier_required");
    Response orders =
        hq.post(
                "/api/v1/reorder-suggestions/orders",
                """
                {"orders":[
                  {"supplierUid":"%s","lines":[{"productUid":"%s","quantity":25,"unitCost":100}]},
                  {"supplierUid":"%s","lines":[{"productUid":"%s","quantity":45,"unitCost":20}]}]}
                """
                    .formatted(supplier, amox, supplier, gloves))
            .expect(201);
    List<Object> created = orders.json().read("$.data");
    assertThat(created).hasSize(2);
    assertThat(orders.string("$.data[0].status")).isEqualTo("ORDERED");

    // What is on order now covers the need: nothing left to suggest.
    assertThat(
            hq.get("/api/v1/reorder-suggestions")
                .expect(200)
                .json()
                .<List<Object>>read("$.data.groups"))
        .isEmpty();
  }

  @Test
  @DisplayName("a locked period refuses back-dated expenses while today's trading still posts")
  void periodLock() {
    Tenant t = tenant("PL");
    Api admin = t.admin();
    Api hq = t.hq();
    LocalDate today = LocalDate.now(ZoneOffset.UTC);

    assertThat(admin.get("/api/v1/finance/period-lock").expect(200).string("$.data.lockedThrough"))
        .isNull();
    assertThat(
            admin
                .put("/api/v1/finance/period-lock", "{\"lockedThrough\":\"%s\"}".formatted(today))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_lock_date");
    LocalDate through = today.minusDays(3);
    Response locked =
        admin
            .put("/api/v1/finance/period-lock", "{\"lockedThrough\":\"%s\"}".formatted(through))
            .expect(200);
    assertThat(locked.string("$.data.lockedThrough")).isEqualTo(through.toString());
    assertThat(locked.string("$.data.changedBy")).isNotBlank();

    String expense =
        """
        {"category":"RENT","amount":100,"paymentMethod":"CASH","description":"Rent",
         "expenseDate":"%s"}
        """;
    assertThat(
            hq.post("/api/v1/expenses", expense.formatted(through)).expect(422).string("$.reason"))
        .isEqualTo("period_locked");
    hq.post("/api/v1/expenses", expense.formatted(today.minusDays(1))).expect(201);

    String product = product(admin, "Cetirizine 10mg", 0);
    hq.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"C1","expiryDate":"%s","quantity":10,"unitCost":50}
            """
                .formatted(product, today.plusYears(1)))
        .expect(201);
    String sale =
        hq.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":2}],
                 "payments":[{"method":"CASH","amount":1000}]}
                """
                    .formatted(UUID.randomUUID(), product))
            .expect(201)
            .string("$.data.number");
    Api.eventually(() -> admin.get("/api/v1/journal?limit=100").expect(200).body().contains(sale));

    // Unlocking reopens the period; it always needs a second person's approval (FRD-10).
    assertThat(
            admin
                .put("/api/v1/finance/period-lock", "{\"lockedThrough\":null}")
                .expect(403)
                .string("$.reason"))
        .isEqualTo("approval_required");
    Api anonymous = api();
    String manager =
        tz.co.pharmacy.support.Staff.login(
                admin, anonymous, "Manager", "BRANCH_MANAGER", "Manager#2026x")
            .signIn();
    String approval =
        admin
            .post(
                "/api/v1/approvals",
                "{\"action\":\"PERIOD_UNLOCK\",\"login\":\"%s\",\"password\":\"Manager#2026x\"}"
                    .formatted(manager))
            .expect(201)
            .string("$.data.token");
    admin
        .put("/api/v1/finance/period-lock", "{\"lockedThrough\":null}", "X-Approval", approval)
        .expect(200);
    hq.post("/api/v1/expenses", expense.formatted(through)).expect(201);
  }

  private record Tenant(Api admin, Api hq) {}

  private Tenant tenant(String prefix) {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = prefix + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Plan %s","legalName":"Plan %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    return new Tenant(admin, admin.in(tenant, branch));
  }

  private static String product(Api admin, String name, int reorderLevel) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
             "sellingPrice":300,"costPrice":90,"reorderLevel":%d}
            """
                .formatted(name, name, reorderLevel))
        .expect(201)
        .string("$.data.uid");
  }
}
