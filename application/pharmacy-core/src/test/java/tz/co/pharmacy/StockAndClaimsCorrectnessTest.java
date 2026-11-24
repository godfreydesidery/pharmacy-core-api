package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Batch 3, money and stock correctness (MON-4, 5, 6, 8, 9, 19): insurance claims decided again or
 * returned keep the claim and the ledger agreeing, and the stock at cost (on the shelves and in
 * transit) keeps equalling the ledger's inventory (1200) through receipts at several costs, a
 * transfer, sales, a return, repacks and a stock-take.
 */
class StockAndClaimsCorrectnessTest extends IntegrationTest {

  /** A write-off given back needs Dr 1110 / Cr 6095, which finance does not post yet. */
  @Autowired DataSource dataSource;

  private Api admin;
  private Api hq;
  private String tenantUid;
  private String supplier;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SC" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenantUid =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Correct %s","legalName":"Correct %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenantUid, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    hq = admin.in(tenantUid, branch);
    supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Lake Zone Drugs\",\"paymentTerms\":\"NET_30\"}")
            .expect(201)
            .string("$.data.uid");
  }

  // ---------------------------------------------------------------- insurance (MON-4, 5, 19)

  private record Insured(String patient, String payer, String member, String product) {}

  private Insured insured() {
    String patient =
        admin
            .post(
                "/api/v1/customers",
                "{\"name\":\"Neema Kileo\",\"phone\":\"+255700000002\",\"type\":\"INSURANCE\"}")
            .expect(201)
            .string("$.data.uid");
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
                "{\"payerUid\":\"%s\",\"name\":\"Full cover\",\"copayType\":\"NONE\",\"copayValue\":0}"
                    .formatted(payer))
            .expect(201)
            .string("$.data.uid");
    String member =
        admin
            .post(
                "/api/v1/insurance/members",
                """
                {"customerUid":"%s","schemeUid":"%s","memberNumber":"NHIF-1","principalName":"Neema",
                 "validFrom":"%s","validUntil":"%s"}
                """
                    .formatted(
                        patient,
                        scheme,
                        LocalDate.now().minusDays(1),
                        LocalDate.now().plusYears(1)))
            .expect(201)
            .string("$.data.uid");
    String insulin = product("Insulin pen", 10_000, 4_000);
    hq.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"INS-1","expiryDate":"%s","quantity":20,"unitCost":4000}
            """
                .formatted(insulin, LocalDate.now().plusYears(1)))
        .expect(201);
    Insured insured = new Insured(patient, payer, member, insulin);
    // The account is made on first use; made now, the reactions do not race to create it.
    patientBalance(insured);
    return insured;
  }

  /** One pen on full cover: the claim is 10,000 and 1110 holds it. */
  private Response insuredSale(Insured insured) {
    Response sale =
        hq.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],"payments":[],
                 "customerUid":"%s","memberUid":"%s","diagnosisCode":"E11"}
                """
                    .formatted(
                        UUID.randomUUID(), insured.product(), insured.patient(), insured.member()))
            .expect(201);
    eventually(() -> Math.abs(account("1110") - 10_000.0) < 0.001);
    return sale;
  }

  private String claimOf(Insured insured) {
    return admin
        .get("/api/v1/insurance/claims?payerUid=" + insured.payer())
        .expect(200)
        .string("$.data.items[0].uid");
  }

  private Response adjudicate(String claim, String product, int approved, String rejectedTo) {
    return admin
        .post(
            "/api/v1/insurance/claims/" + claim + "/adjudicate",
            """
            {"lines":[{"productUid":"%s","quantity":1,"approvedAmount":%d}],"rejectedTo":"%s"}
            """
                .formatted(product, approved, rejectedTo))
        .expect(200);
  }

  private double patientBalance(Insured insured) {
    return money(
        admin.get("/api/v1/receivables/accounts/" + insured.patient()).expect(200),
        "$.data.balance");
  }

  @Test
  @DisplayName(
      "MON-4: decided at 6,000 then 8,000 to the patient: the change moves, 1110 nets to 0")
  void readjudicationToThePatient() {
    Insured insured = insured();
    insuredSale(insured);
    String claim = claimOf(insured);

    adjudicate(claim, insured.product(), 6_000, "PATIENT_ACCOUNT");
    eventually(() -> Math.abs(account("1110") - 6_000.0) < 0.001);
    eventually(() -> Math.abs(patientBalance(insured) - 4_000.0) < 0.001);

    // On appeal the payer approves 8,000: the patient owes 2,000 less, and 1110 holds 8,000.
    adjudicate(claim, insured.product(), 8_000, "PATIENT_ACCOUNT");
    eventually(() -> Math.abs(account("1110") - 8_000.0) < 0.001);
    eventually(() -> Math.abs(patientBalance(insured) - 2_000.0) < 0.001);

    Response paid =
        admin
            .post(
                "/api/v1/insurance/remittances",
                "{\"payerUid\":\"%s\",\"amount\":8000,\"reference\":\"EFT-1\"}"
                    .formatted(insured.payer()))
            .expect(201);
    assertThat(paid.string("$.data[0].status")).isEqualTo("PAID");
    eventually(() -> Math.abs(account("1110")) < 0.001);
    assertThat(account("1100")).isEqualTo(2_000.0);
    assertThat(patientBalance(insured)).isEqualTo(2_000.0);
  }

  @Test
  @DisplayName("MON-4: decided at 6,000 then 8,000, written off: 1110 nets to 0, 6095 holds 2,000")
  void readjudicationWrittenOff() {
    Insured insured = insured();
    insuredSale(insured);
    String claim = claimOf(insured);

    adjudicate(claim, insured.product(), 6_000, "WRITE_OFF");
    eventually(() -> Math.abs(account("1110") - 6_000.0) < 0.001);
    adjudicate(claim, insured.product(), 8_000, "WRITE_OFF");
    eventually(() -> Math.abs(account("1110") - 8_000.0) < 0.001);
    admin
        .post(
            "/api/v1/insurance/remittances",
            "{\"payerUid\":\"%s\",\"amount\":8000,\"reference\":\"EFT-1\"}"
                .formatted(insured.payer()))
        .expect(201);
    assertThat(admin.get("/api/v1/insurance/claims/" + claim).expect(200).string("$.data.status"))
        .isEqualTo("PAID");
    eventually(() -> Math.abs(account("1110")) < 0.001);
    assertThat(account("6095")).isEqualTo(2_000.0);
  }

  @Test
  @DisplayName(
      "MON-5: a return on a claim rejected to the patient comes off their account, not 1110")
  void returnOnRejectedToPatient() {
    Insured insured = insured();
    Response sale = insuredSale(insured);
    String claim = claimOf(insured);
    adjudicate(claim, insured.product(), 0, "PATIENT_ACCOUNT");
    eventually(() -> Math.abs(patientBalance(insured) - 10_000.0) < 0.001);
    eventually(() -> Math.abs(account("1110")) < 0.001);

    hq.post(
            "/api/v1/sales/" + sale.string("$.data.uid") + "/returns",
            """
            {"reason":"Not needed","lines":[{"saleLineUid":"%s","quantity":1}]}
            """
                .formatted(sale.string("$.data.lines[0].uid")))
        .expect(201);

    eventually(() -> Math.abs(patientBalance(insured)) < 0.001);
    eventually(() -> Math.abs(account("1100")) < 0.001);
    // Before: the return credited 1110 a second time, leaving it at -10,000.
    assertThat(account("1110")).isEqualTo(0.0);
    assertThat(account("1000")).isEqualTo(0.0);
  }

  @Test
  @DisplayName("MON-5: a return on a written-off claim gives the write-off back, not 1110")
  void returnOnWrittenOff() {
    Insured insured = insured();
    Response sale = insuredSale(insured);
    adjudicate(claimOf(insured), insured.product(), 0, "WRITE_OFF");
    eventually(() -> Math.abs(account("6095") - 10_000.0) < 0.001);
    hq.post(
            "/api/v1/sales/" + sale.string("$.data.uid") + "/returns",
            """
            {"reason":"Not needed","lines":[{"saleLineUid":"%s","quantity":1}]}
            """
                .formatted(sale.string("$.data.lines[0].uid")))
        .expect(201);
    eventually(() -> Math.abs(account("6095")) < 0.001);
    assertThat(account("1110")).isEqualTo(0.0);
  }

  @Test
  @DisplayName("MON-19: a remittance refuses a negative amount for a claim")
  void negativeAllocation() {
    Insured insured = insured();
    insuredSale(insured);
    String claim = claimOf(insured);
    adjudicate(claim, insured.product(), 10_000, "WRITE_OFF");

    Response refused =
        admin.post(
            "/api/v1/insurance/remittances",
            "{\"payerUid\":\"%s\",\"amount\":100,\"perClaim\":{\"%s\":-100}}"
                .formatted(insured.payer(), claim));
    assertThat(refused.expect(422).string("$.reason")).isEqualTo("invalid_amount");
  }

  // ---------------------------------------------------------------- stock-take (MON-6)

  @Test
  @DisplayName(
      "MON-6: sales during a stock-take are not losses, and a batch sold out meanwhile posts")
  void countAgainstMovingStock() {
    String amox = product("Amoxicillin 500mg", 300, 120);
    String zinc = product("Zinc 20mg", 200, 80);
    opening(amox, "AM-1", 100, 120);
    opening(zinc, "ZN-1", 5, 80);

    Response sheet = hq.post("/api/v1/stock-counts", "{\"note\":\"Month end\"}").expect(201);
    String count = sheet.string("$.data.uid");
    String amoxLine = lineOf(sheet, "AM-1");
    String zincLine = lineOf(sheet, "ZN-1");

    // While the shelves are counted, the till sells 10 Amoxicillin and every Zinc.
    sell(amox, 10, 3_000);
    sell(zinc, 5, 1_000);
    hq.put(
            "/api/v1/stock-counts/" + count + "/counts",
            "[{\"lineUid\":\"%s\",\"counted\":90},{\"lineUid\":\"%s\",\"counted\":0}]"
                .formatted(amoxLine, zincLine))
        .expect(200);
    // The count is blind to the counter (FRD-12); its reviewer sees the variance.
    Response before = hq.get("/api/v1/stock-counts/" + count + "/review").expect(200);
    assertThat(money(before, "$.data.varianceValue")).isEqualTo(0.0);

    // Before: 90 − 100 posted 10 lost, and 0 − 5 on an empty batch was refused (insufficient).
    Response posted = hq.post("/api/v1/stock-counts/" + count + "/post", "{}").expect(200);
    assertThat(posted.string("$.data.status")).isEqualTo("POSTED");
    assertThat(money(posted, "$.data.varianceValue")).isEqualTo(0.0);
    List<Object> adjustments =
        hq.get("/api/v1/stock/movements?type=ADJUSTMENT").expect(200).json().read("$.data.items");
    assertThat(adjustments).isEmpty();
    assertThat(onHand(amox)).isEqualTo(90);

    // A real shortage still posts: one more sold, then 87 counted of the 89 on the books is 2 lost.
    Response second = hq.post("/api/v1/stock-counts", "{}").expect(201);
    sell(amox, 1, 300);
    hq.put(
            "/api/v1/stock-counts/" + second.string("$.data.uid") + "/counts",
            "[{\"lineUid\":\"%s\",\"counted\":87}]".formatted(lineOf(second, "AM-1")))
        .expect(200);
    Response shortage =
        hq.post("/api/v1/stock-counts/" + second.string("$.data.uid") + "/post", "{}").expect(200);
    assertThat(money(shortage, "$.data.varianceValue")).isEqualTo(-240.0);
    assertThat(onHand(amox)).isEqualTo(87);
  }

  // ---------------------------------------------------------------- stock at cost = 1200

  @Test
  @DisplayName(
      "MON-8, MON-9: through receipts, transit, sale, return, repacks and a count, stock at cost"
          + " equals ledger 1200")
  void stockAtCostEqualsTheLedger() {
    admin.put("/api/v1/settings", "{\"acceptedCurrencies\":[\"TZS\",\"USD\"]}").expect(200);
    admin
        .post(
            "/api/v1/fx/rates",
            "{\"base\":\"USD\",\"quote\":\"TZS\",\"rate\":2650,\"effectiveOn\":\"%s\"}"
                .formatted(LocalDate.now()))
        .expect(201);
    String second =
        admin
            .post("/api/v1/branches", "{\"code\":\"B2\",\"name\":\"Arusha\"}")
            .expect(201)
            .string("$.data.uid");
    String metformin = product("Metformin 500mg", 500, 200);
    String packs = product("Metformin own pack", 2_000, 0);
    LocalDate firstExpiry = LocalDate.now().plusMonths(10);

    receive(metformin, "MET-1", firstExpiry, 10, "200", null);
    // 4 leave for Arusha and stay in transit while more of the lot arrives at another cost: the
    // average counts them, (2,000 + 1,260.06) / 16 → 203.75.
    hq.post(
            "/api/v1/transfers",
            """
            {"toBranchUid":"%s","lines":[{"productUid":"%s","quantity":4}]}
            """
                .formatted(second, metformin))
        .expect(201);
    receive(metformin, "MET-1", firstExpiry, 6, "210.01", null);
    assertThat(batchCost(metformin, "MET-1")).isEqualTo(203.75);
    booksMatch("a receipt averaged with units in transit");

    Response sale = sell(metformin, 2, 1_000);
    booksMatch("a sale");
    // The lot is re-priced again; the return comes back at 203.75 into a batch now at 210.66.
    receive(metformin, "MET-1", firstExpiry, 5, "230", null);
    assertThat(batchCost(metformin, "MET-1")).isEqualTo(210.66);
    booksMatch("a second re-pricing");
    hq.post(
            "/api/v1/sales/" + sale.string("$.data.uid") + "/returns",
            """
            {"reason":"Changed","refundMethod":"CASH","lines":[{"saleLineUid":"%s","quantity":1}]}
            """
                .formatted(sale.string("$.data.lines[0].uid")))
        .expect(201);
    booksMatch("a return restocked at the newer cost");

    // A dollar invoice: 7 at USD 0.37 and 2,650.1234 costs 980.5457 each, kept as 980.55.
    receive(metformin, "MET-2", LocalDate.now().plusMonths(20), 7, "0.37", "2650.1234");
    booksMatch("a dollar receipt");

    // Repacked twice into the same own-pack batch, three tablets a pack as its pack link says
    // (FRD-2: a repack follows its recipe exactly): 3 × 210.66 = 631.98 a pack.
    admin
        .put(
            "/api/v1/pack-links",
            "{\"packProductUid\":\"%s\",\"unitProductUid\":\"%s\",\"unitsPerPack\":3}"
                .formatted(packs, metformin))
        .expect(200);
    repack(metformin, 3, packs, 1, firstExpiry);
    repack(metformin, 6, packs, 2, firstExpiry);
    assertThat(batchCost(packs, "PK-1")).isEqualTo(631.98);
    booksMatch("two repacks into one batch");

    // A stock-take with a sale in the middle and one tablet really missing.
    Response sheet = hq.post("/api/v1/stock-counts", "{}").expect(201);
    String line = lineOf(sheet, "MET-1");
    // The sheet is blind (FRD-12); the reviewer sees what is expected.
    int expected =
        expectedOf(
            hq.get("/api/v1/stock-counts/" + sheet.string("$.data.uid") + "/review").expect(200),
            "MET-1");
    sell(metformin, 1, 500);
    hq.put(
            "/api/v1/stock-counts/" + sheet.string("$.data.uid") + "/counts",
            "[{\"lineUid\":\"%s\",\"counted\":%d}]".formatted(line, expected - 2))
        .expect(200);
    hq.post("/api/v1/stock-counts/" + sheet.string("$.data.uid") + "/post", "{}").expect(200);

    assertThat(valueInTransit()).isPositive();
    booksMatch("a stock-take");
  }

  /** sum(stock levels × batch unit cost) + value in transit == ledger 1200, once posted. */
  private void booksMatch(String step) {
    try {
      eventually(() -> drift().signum() == 0);
    } catch (AssertionError notYet) {
      // Reported below with the figures.
    }
    assertThat(drift())
        .as("ledger 1200 less stock at cost, after " + step)
        .isEqualByComparingTo("0");
  }

  private BigDecimal drift() {
    List<Object> balance =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '1200')].balance");
    BigDecimal ledger =
        balance.isEmpty() ? BigDecimal.ZERO : new BigDecimal(balance.getFirst().toString());
    return ledger.subtract(stockAtCost()).subtract(valueInTransit());
  }

  // ---------------------------------------------------------------- helpers

  private String product(String name, int price, int cost) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
             "sellingPrice":%d,"costPrice":%d,"taxRatePercent":0}
            """
                .formatted(name, name, price, cost))
        .expect(201)
        .string("$.data.uid");
  }

  private void opening(String product, String batch, int quantity, int cost) {
    hq.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%d}
            """
                .formatted(product, batch, LocalDate.now().plusYears(1), quantity, cost))
        .expect(201);
  }

  private void receive(
      String product, String batch, LocalDate expiry, int quantity, String cost, String usdRate) {
    String currency =
        usdRate == null ? "" : ",\"currency\":\"USD\",\"exchangeRate\":%s".formatted(usdRate);
    hq.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"%s","lines":[
              {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%s}]%s}
            """
                .formatted(
                    supplier,
                    "INV-" + UUID.randomUUID().toString().substring(0, 8),
                    product,
                    batch,
                    expiry,
                    quantity,
                    cost,
                    currency))
        .expect(201);
  }

  private Response sell(String product, int quantity, int cash) {
    return hq.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":%d}],
             "payments":[{"method":"CASH","amount":%d}]}
            """
                .formatted(UUID.randomUUID(), product, quantity, cash))
        .expect(201);
  }

  private void repack(String input, int quantity, String output, int made, LocalDate expiry) {
    hq.post(
            "/api/v1/stock/transformations",
            """
            {"type":"REPACK","inputs":[{"productUid":"%s","quantity":%d}],
             "output":{"productUid":"%s","quantity":%d,"batchNumber":"PK-1","expiryDate":"%s"}}
            """
                .formatted(input, quantity, output, made, expiry))
        .expect(201);
  }

  private static String lineOf(Response sheet, String batch) {
    List<String> uids = sheet.json().read("$.data.items[?(@.batchNumber == '" + batch + "')].uid");
    return uids.getFirst();
  }

  private static int expectedOf(Response sheet, String batch) {
    List<Integer> expected =
        sheet.json().read("$.data.items[?(@.batchNumber == '" + batch + "')].expected");
    return expected.getFirst();
  }

  private int onHand(String product) {
    List<Object> quantities =
        hq.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[*].quantity");
    return quantities.stream().mapToInt(q -> ((Number) q).intValue()).sum();
  }

  private double batchCost(String product, String batch) {
    List<Object> costs =
        hq.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[?(@.batchNumber == '" + batch + "')].unitCost");
    return Double.parseDouble(costs.getFirst().toString());
  }

  private double account(String code) {
    List<Object> balance =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + code + "')].balance");
    return balance.isEmpty() ? 0.0 : Double.parseDouble(balance.getFirst().toString());
  }

  /** Every branch's stock, each batch at its unit cost. */
  private BigDecimal stockAtCost() {
    return sum(
        "select coalesce(sum(s.quantity * b.unit_cost), 0) from inventory.stock_levels s"
            + " join inventory.batches b on b.id = s.batch_id");
  }

  /** Units dispatched and not yet received, at their batch's unit cost. */
  private BigDecimal valueInTransit() {
    return sum(
        "select coalesce(sum(l.quantity * b.unit_cost), 0) from inventory.transfer_lines l"
            + " join inventory.transfers t on t.id = l.transfer_id"
            + " join inventory.batches b on b.uid = l.batch_uid where t.status = 'IN_TRANSIT'");
  }

  /** Reads as the application does: in one transaction scoped to this pharmacy (forced RLS). */
  private BigDecimal sum(String sql) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement scope =
          connection.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        scope.setString(1, tenantUid);
        scope.execute();
      }
      try (PreparedStatement query = connection.prepareStatement(sql);
          ResultSet rows = query.executeQuery()) {
        rows.next();
        BigDecimal value = rows.getBigDecimal(1);
        connection.rollback();
        return value;
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private static double money(Response response, String path) {
    Object value = response.json().read(path);
    return value instanceof Number number
        ? number.doubleValue()
        : Double.parseDouble((String) value);
  }
}
