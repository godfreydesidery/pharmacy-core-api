package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Pricing plans: Normal Sale is each medicine's own price; a plan replaces it for the medicines it
 * prices, applies its rule to the rest, or blocks them when it has none. The till takes the
 * customer's plan; switching needs the permission. Every invoice keeps its plan and price; every
 * price change is in the price history.
 */
class PricingPlansTest extends IntegrationTest {

  private Api admin;
  private Api adminTill;
  private Api cashierTill;
  private String paracetamol;
  private String amoxicillin;
  private String wholesalePlan;
  private String hospitalPlan;
  private String clinic;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "PP" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Plans %s","legalName":"Plans %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    adminTill = admin.in(tenant, branch);
    tz.co.pharmacy.support.Staff.Login cashier =
        tz.co.pharmacy.support.Staff.login(admin, anonymous, "Till", "CASHIER", "Cashier#2026x");
    cashierTill =
        anonymous.as(anonymous.signIn(cashier.signIn(), "Cashier#2026x")).in(tenant, branch);

    paracetamol = product("Paracetamol 500mg", 500, 200);
    amoxicillin = product("Amoxicillin 500mg", 1000, 600);
    for (String p : List.of(paracetamol, amoxicillin)) {
      adminTill
          .post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"B-%s","expiryDate":"%s","quantity":200,"unitCost":100}
              """
                  .formatted(p, p.substring(0, 6), LocalDate.now().plusYears(2)))
          .expect(201);
    }
    // Wholesale: for wholesale customers, no rule (a medicine without a price is blocked).
    wholesalePlan =
        admin
            .post(
                "/api/v1/price-lists",
                """
                {"name":"Wholesale","customerType":"WHOLESALE",
                 "items":[{"productUid":"%s","price":400}]}
                """
                    .formatted(paracetamol))
            .expect(201)
            .string("$.data.uid");
    // Hospital: for anyone chosen, Normal − 20% where it has no price.
    hospitalPlan =
        admin
            .post("/api/v1/price-lists", "{\"name\":\"Hospital\",\"adjustmentPercent\":-20}")
            .expect(201)
            .string("$.data.uid");
    clinic =
        admin
            .post(
                "/api/v1/customers",
                "{\"name\":\"City Clinic\",\"phone\":\"+255700300400\",\"type\":\"WHOLESALE\"}")
            .expect(201)
            .string("$.data.uid");
  }

  private String product(String name, int price, int cost) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
             "productType":"MEDICINE","trackingMode":"BATCH","sellingPrice":%d,"costPrice":%d,
             "taxRatePercent":0}
            """
                .formatted(name, name.split(" ")[0], price, cost))
        .expect(201)
        .string("$.data.uid");
  }

  private Response sell(Api till, String customer, String plan, String product, int qty, int paid) {
    return till.post(
        "/api/v1/sales",
        """
        {"idempotencyKey":"%s",%s%s"lines":[{"productUid":"%s","quantity":%d}],
         "payments":[{"method":"CASH","amount":%d}]}
        """
            .formatted(
                UUID.randomUUID(),
                customer == null ? "" : "\"customerUid\":\"" + customer + "\",",
                plan == null ? "" : "\"pricePlan\":\"" + plan + "\",",
                product,
                qty,
                paid));
  }

  @Test
  @DisplayName("the till takes the customer's plan; switching needs the permission")
  void tillPlans() {
    // Walk-in: Normal Sale. The clinic (wholesale): its plan, chosen for it.
    Response walkIn = cashierTill.get("/api/v1/pos/price-plans").expect(200);
    assertThat(walkIn.string("$.data.defaultPlan.name")).isEqualTo("Normal Sale");
    assertThat(walkIn.read("$.data.canChange", Boolean.class)).isFalse();
    Response forClinic =
        cashierTill.get("/api/v1/pos/price-plans?customerUid=" + clinic).expect(200);
    assertThat(forClinic.string("$.data.defaultPlan.name")).isEqualTo("Wholesale");
    assertThat(
            adminTill
                .get("/api/v1/pos/price-plans")
                .expect(200)
                .read("$.data.canChange", Boolean.class))
        .isTrue();

    Response clinicSale = sell(cashierTill, clinic, null, paracetamol, 10, 4000).expect(201);
    assertThat(clinicSale.read("$.data.total", Double.class)).isEqualTo(4000.0);
    assertThat(clinicSale.string("$.data.priceListName")).isEqualTo("Wholesale");

    // A cashier may not switch plans; the owner may.
    Response refused = sell(cashierTill, null, hospitalPlan, paracetamol, 1, 400).expect(403);
    assertThat(refused.string("$.reason")).isEqualTo("price_plan_not_allowed");
    Response hospital = sell(adminTill, null, hospitalPlan, paracetamol, 1, 400).expect(201);
    assertThat(hospital.read("$.data.total", Double.class)).isEqualTo(400.0);
    assertThat(hospital.string("$.data.priceListName")).isEqualTo("Hospital");
    // Asking for the customer's own plan is no switch, so the cashier may.
    sell(cashierTill, clinic, wholesalePlan, paracetamol, 1, 400).expect(201);
    // The owner may sell the clinic at Normal Sale.
    Response normal = sell(adminTill, clinic, "NORMAL", paracetamol, 1, 500).expect(201);
    assertThat(normal.read("$.data.total", Double.class)).isEqualTo(500.0);
    assertThat(normal.string("$.data.priceListName")).isEqualTo("Normal Sale");
  }

  @Test
  @DisplayName("a plan without a price and without a rule blocks the medicine; a rule prices it")
  void blockUnlessRule() {
    // Wholesale has no Amoxicillin price and no rule: the till shows it unpriced, checkout refuses.
    Response quote =
        cashierTill
            .post(
                "/api/v1/pos/prices",
                """
                {"customerUid":"%s","lines":[{"productUid":"%s","quantity":1}]}
                """
                    .formatted(clinic, amoxicillin))
            .expect(200);
    assertThat(quote.read("$.data[0].unpriced", Boolean.class)).isTrue();
    Response blocked = sell(cashierTill, clinic, null, amoxicillin, 1, 1000).expect(422);
    assertThat(blocked.string("$.reason")).isEqualTo("no_plan_price");
    assertThat(blocked.string("$.detail")).contains("Wholesale").contains("Amoxicillin");
    // Hospital's rule (Normal − 20%) prices it.
    Response ruled = sell(adminTill, null, hospitalPlan, amoxicillin, 1, 800).expect(201);
    assertThat(ruled.read("$.data.total", Double.class)).isEqualTo(800.0);
    // Once Wholesale prices it, the clinic can buy it.
    admin
        .put("/api/v1/pricing-plans/" + wholesalePlan + "/prices/" + amoxicillin, "{\"price\":750}")
        .expect(200);
    assertThat(
            sell(cashierTill, clinic, null, amoxicillin, 2, 1500)
                .expect(201)
                .read("$.data.total", Double.class))
        .isEqualTo(1500.0);
  }

  @Test
  @DisplayName("the grid, its missing filter, price history, and invoices keep their price")
  void gridHistoryAndSnapshots() {
    Response grid = admin.get("/api/v1/pricing-plans/grid").expect(200);
    List<String> names = grid.json().read("$.data.items[*].name");
    assertThat(names).containsExactly("Amoxicillin 500mg", "Paracetamol 500mg");
    assertThat(grid.read("$.data.items[1].prices['" + wholesalePlan + "']", Double.class))
        .isEqualTo(400.0);
    // Missing: Amoxicillin has no Wholesale price (Hospital has a rule, so it is never missing).
    List<String> missing =
        admin
            .get("/api/v1/pricing-plans/grid?missing=true")
            .expect(200)
            .json()
            .read("$.data.items[*].name");
    assertThat(missing).containsExactly("Amoxicillin 500mg");
    List<String> plans =
        admin.get("/api/v1/pricing-plans").expect(200).json().read("$.data[*].name");
    assertThat(plans).containsExactly("Normal Sale", "Hospital", "Wholesale");

    // A sale, then the prices change: the invoice keeps what it charged.
    Response sold = sell(cashierTill, clinic, null, paracetamol, 2, 800).expect(201);
    admin
        .put("/api/v1/pricing-plans/" + wholesalePlan + "/prices/" + paracetamol, "{\"price\":450}")
        .expect(200);
    admin.put("/api/v1/pricing-plans/normal/prices/" + paracetamol, "{\"price\":550}").expect(200);
    Response again = adminTill.get("/api/v1/sales/" + sold.string("$.data.uid")).expect(200);
    assertThat(again.read("$.data.total", Double.class)).isEqualTo(800.0);
    assertThat(again.read("$.data.lines[0].unitPrice", Double.class)).isEqualTo(400.0);
    assertThat(again.string("$.data.priceListName")).isEqualTo("Wholesale");

    // History, newest first: Normal 500 → 550, Wholesale 400 → 450, Wholesale added at 400,
    // Normal set at 500 when registered.
    Response history = admin.get("/api/v1/products/" + paracetamol + "/price-history").expect(200);
    List<String> plansChanged = history.json().read("$.data[*].planName");
    List<String> sources = history.json().read("$.data[*].source");
    assertThat(plansChanged)
        .containsExactly("Normal Sale", "Wholesale", "Wholesale", "Normal Sale");
    assertThat(sources).containsExactly("MANUAL", "MANUAL", "MANUAL", "PRODUCT");
    assertThat(history.read("$.data[0].oldPrice", Double.class)).isEqualTo(500.0);
    assertThat(history.read("$.data[0].newPrice", Double.class)).isEqualTo(550.0);

    // Removing a plan price puts the medicine back to missing.
    admin.delete("/api/v1/pricing-plans/" + wholesalePlan + "/prices/" + paracetamol).expect(200);
    List<String> nowMissing =
        admin
            .get("/api/v1/pricing-plans/grid?missing=true")
            .expect(200)
            .json()
            .read("$.data.items[*].name");
    assertThat(nowMissing).containsExactly("Amoxicillin 500mg", "Paracetamol 500mg");
  }

  @Test
  @DisplayName("bulk update: previewed, rounded, only missing; then saved with its history")
  void bulkUpdate() {
    // Wholesale, only where missing: Amoxicillin 1000 − 12% = 880 → nearest 50 = 900.
    Response preview =
        admin
            .post(
                "/api/v1/pricing-plans/" + wholesalePlan + "/bulk/preview",
                "{\"percent\":-12,\"roundTo\":50,\"rounding\":\"NEAREST\",\"onlyMissing\":true}")
            .expect(200);
    assertThat(preview.read("$.data.applied", Boolean.class)).isFalse();
    List<String> previewed = preview.json().read("$.data.lines[*].name");
    assertThat(previewed).containsExactly("Amoxicillin 500mg");
    assertThat(preview.read("$.data.lines[0].newPrice", Double.class)).isEqualTo(900.0);
    // Nothing saved by a preview.
    assertThat(
            admin
                .get("/api/v1/pricing-plans/grid?q=amox")
                .expect(200)
                .json()
                .read("$.data.items[0].prices", java.util.Map.class))
        .doesNotContainKey(wholesalePlan);
    admin
        .post(
            "/api/v1/pricing-plans/" + wholesalePlan + "/bulk",
            "{\"percent\":-12,\"roundTo\":50,\"rounding\":\"NEAREST\",\"onlyMissing\":true}")
        .expect(200);
    Response grid = admin.get("/api/v1/pricing-plans/grid").expect(200);
    assertThat(grid.read("$.data.items[0].prices['" + wholesalePlan + "']", Double.class))
        .isEqualTo(900.0);
    assertThat(grid.read("$.data.items[1].prices['" + wholesalePlan + "']", Double.class))
        .isEqualTo(400.0);

    // Normal Sale +5%, rounded up to 50: 500 → 525 → 550; 1000 → 1050.
    admin
        .post(
            "/api/v1/pricing-plans/normal/bulk",
            "{\"percent\":5,\"roundTo\":50,\"rounding\":\"UP\"}")
        .expect(200);
    Response after = admin.get("/api/v1/pricing-plans/grid").expect(200);
    assertThat(after.read("$.data.items[0].normalPrice", Double.class)).isEqualTo(1050.0);
    assertThat(after.read("$.data.items[1].normalPrice", Double.class)).isEqualTo(550.0);
    List<String> sources =
        admin
            .get("/api/v1/products/" + amoxicillin + "/price-history")
            .expect(200)
            .json()
            .read("$.data[*].source");
    assertThat(sources).startsWith("BULK", "BULK");
  }

  @Test
  @DisplayName("an insurer's plan is the default for its members; the report compares plans")
  void insurerPlanAndReport() {
    String payer =
        admin
            .post(
                "/api/v1/insurance/payers",
                "{\"code\":\"NHIF\",\"name\":\"NHIF\",\"paymentTermsDays\":30,\"requiresPreauth\":false}")
            .expect(201)
            .string("$.data.uid");
    String scheme =
        admin
            .post(
                "/api/v1/insurance/schemes",
                "{\"payerUid\":\"%s\",\"name\":\"Standard\",\"copayType\":\"PERCENT\",\"copayValue\":10}"
                    .formatted(payer))
            .expect(201)
            .string("$.data.uid");
    String patient =
        admin
            .post("/api/v1/customers", "{\"name\":\"Neema Juma\",\"phone\":\"+255700300401\"}")
            .expect(201)
            .string("$.data.uid");
    String member =
        admin
            .post(
                "/api/v1/insurance/members",
                """
                {"customerUid":"%s","schemeUid":"%s","memberNumber":"NHIF-9","principalName":"Neema",
                 "validFrom":"%s","validUntil":"%s"}
                """
                    .formatted(
                        patient,
                        scheme,
                        LocalDate.now().minusDays(1),
                        LocalDate.now().plusYears(1)))
            .expect(201)
            .string("$.data.uid");
    admin
        .post(
            "/api/v1/price-lists",
            """
            {"name":"NHIF tariff","payerUid":"%s","payerName":"NHIF","adjustmentPercent":0,
             "items":[{"productUid":"%s","price":450}]}
            """
                .formatted(payer, paracetamol))
        .expect(201);
    // The member's sale defaults to the insurer's plan; the same customer uninsured, to Normal.
    Response insured =
        cashierTill
            .get("/api/v1/pos/price-plans?customerUid=" + patient + "&memberUid=" + member)
            .expect(200);
    assertThat(insured.string("$.data.defaultPlan.name")).isEqualTo("NHIF tariff");
    assertThat(
            cashierTill
                .get("/api/v1/pos/price-plans?customerUid=" + patient)
                .expect(200)
                .string("$.data.defaultPlan.name"))
        .isEqualTo("Normal Sale");
    Response quote =
        cashierTill
            .post(
                "/api/v1/pos/prices",
                """
                {"customerUid":"%s","memberUid":"%s","lines":[{"productUid":"%s","quantity":1}]}
                """
                    .formatted(patient, member, paracetamol))
            .expect(200);
    assertThat(quote.read("$.data[0].unitPrice", Double.class)).isEqualTo(450.0);

    // The report: a wholesale sale (10 at 400 instead of 500) and a normal one.
    sell(cashierTill, clinic, null, paracetamol, 10, 4000).expect(201);
    sell(cashierTill, null, null, paracetamol, 2, 1000).expect(201);
    String today = LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam")).toString();
    Response report =
        admin.get("/api/v1/reports/pricing-plans?from=" + today + "&to=" + today).expect(200);
    List<Double> belowNormal = report.json().read("$.data[?(@.plan == 'Wholesale')].belowNormal");
    assertThat(belowNormal).containsExactly(1000.0);
    List<Double> normalBelow = report.json().read("$.data[?(@.plan == 'Normal Sale')].belowNormal");
    assertThat(normalBelow).containsExactly(0.0);
  }

  @Test
  @DisplayName("plan pages: cards, a plan's priced medicines only, breaks, prices in plans")
  void planPages() {
    List<String> cards =
        admin.get("/api/v1/pricing-plans/cards").expect(200).json().read("$.data[*].name");
    assertThat(cards).containsExactly("Normal Sale", "Hospital", "Wholesale");
    List<Integer> wholesaleCount =
        admin
            .get("/api/v1/pricing-plans/cards")
            .expect(200)
            .json()
            .read("$.data[?(@.name == 'Wholesale')].priced");
    assertThat(wholesaleCount).containsExactly(1);

    // Wholesale lists only what it prices; "not priced" lists the rest.
    List<String> priced =
        admin
            .get("/api/v1/pricing-plans/" + wholesalePlan + "/items")
            .expect(200)
            .json()
            .read("$.data.items[*].name");
    assertThat(priced).containsExactly("Paracetamol 500mg");
    List<String> notPriced =
        admin
            .get("/api/v1/pricing-plans/" + wholesalePlan + "/items?notPriced=true")
            .expect(200)
            .json()
            .read("$.data.items[*].name");
    assertThat(notPriced).containsExactly("Amoxicillin 500mg");
    // Normal Sale lists every medicine.
    List<String> normal =
        admin
            .get("/api/v1/pricing-plans/normal/items")
            .expect(200)
            .json()
            .read("$.data.items[*].name");
    assertThat(normal).containsExactly("Amoxicillin 500mg", "Paracetamol 500mg");

    // A quantity break is a row of its own.
    admin
        .put(
            "/api/v1/pricing-plans/" + wholesalePlan + "/prices/" + paracetamol + "?minQuantity=10",
            "{\"price\":350}")
        .expect(200);
    List<Integer> minimums =
        admin
            .get("/api/v1/pricing-plans/" + wholesalePlan + "/items")
            .expect(200)
            .json()
            .read("$.data.items[*].minQuantity");
    assertThat(minimums).containsExactly(1, 10);
    assertThat(
            sell(cashierTill, clinic, null, paracetamol, 10, 3500)
                .expect(201)
                .read("$.data.total", Double.class))
        .isEqualTo(3500.0);
    admin
        .delete(
            "/api/v1/pricing-plans/" + wholesalePlan + "/prices/" + paracetamol + "?minQuantity=10")
        .expect(200);

    // One medicine across plans: set, by rule, or blocked.
    Response prices = admin.get("/api/v1/products/" + amoxicillin + "/plan-prices").expect(200);
    List<String> bases = prices.json().read("$.data[*].basis");
    assertThat(bases).containsExactly("PRICE", "RULE", "BLOCKED");
    assertThat(prices.read("$.data[1].price", Double.class)).isEqualTo(800.0);

    // Excel: priced only by default, every medicine on request.
    byte[] pricedOnly = admin.download("/api/v1/price-lists/" + wholesalePlan + "/import/template");
    byte[] everything =
        admin.download("/api/v1/price-lists/" + wholesalePlan + "/import/template?all=true");
    assertThat(everything.length).isGreaterThan(pricedOnly.length);
  }

  @Test
  @DisplayName("Excel: the template lists every medicine; a filled file is checked, then saved")
  void excelRoundTrip() {
    byte[] template = admin.download("/api/v1/price-lists/" + wholesalePlan + "/import/template");
    assertThat(template.length).isGreaterThan(1000);
    String codeA =
        admin.get("/api/v1/pricing-plans/grid?q=amox").expect(200).string("$.data.items[0].code");
    String codeP =
        admin.get("/api/v1/pricing-plans/grid?q=parac").expect(200).string("$.data.items[0].code");
    byte[] file =
        String.join(
                "\n",
                "Code,Product,Barcode,Selling price,Min quantity",
                codeA + ",Amoxicillin 500mg,,780,",
                codeP + ",Paracetamol 500mg,,,")
            .getBytes(StandardCharsets.UTF_8);
    Response check =
        admin
            .upload("/api/v1/price-lists/" + wholesalePlan + "/import/check", "prices.csv", file)
            .expect(200);
    assertThat(check.json().read("$.data.problems", List.class)).isEmpty();
    assertThat(check.read("$.data.saved", Boolean.class)).isFalse();
    admin
        .upload("/api/v1/price-lists/" + wholesalePlan + "/import", "prices.csv", file)
        .expect(200);
    Response grid = admin.get("/api/v1/pricing-plans/grid").expect(200);
    // Amoxicillin priced by the file; Paracetamol (no price in the file) kept at 400.
    assertThat(grid.read("$.data.items[0].prices['" + wholesalePlan + "']", Double.class))
        .isEqualTo(780.0);
    assertThat(grid.read("$.data.items[1].prices['" + wholesalePlan + "']", Double.class))
        .isEqualTo(400.0);
    List<String> sources =
        admin
            .get("/api/v1/products/" + amoxicillin + "/price-history")
            .expect(200)
            .json()
            .read("$.data[*].source");
    assertThat(sources.getFirst()).isEqualTo("IMPORT");
  }
}
