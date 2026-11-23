package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Money correctness of sales (adversarial review §3.2): refunds go back the way the sale was paid,
 * an insured sale's member is the customer's, the credit limit holds under concurrent sales, tills
 * selling the same products in opposite order never fail, the coverage quote is priced as the sale,
 * a push payment's excess can go on account, partial returns lose no cent, and the sales list cuts
 * days at the pharmacy's midnight.
 */
class MoneyCorrectnessSalesTest extends IntegrationTest {

  @Autowired DataSource dataSource;

  private Api anonymous;
  private Api root;
  private Api admin;
  private Api till;
  private Api second;
  private String tenantUid;
  private String branchUid;
  private String secondUid;
  private String product;
  private String clinic;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "MC" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenantUid =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Correct %s","legalName":"Correct %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenantUid, null);
    branchUid =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    secondUid =
        admin
            .post("/api/v1/branches", "{\"code\":\"B2\",\"name\":\"Arusha\"}")
            .expect(201)
            .string("$.data.uid");
    till = admin.in(tenantUid, branchUid);
    second = admin.in(tenantUid, secondUid);
    product = product("Amoxicillin 500mg", "10000");
    stock(till, product, 500);
    clinic =
        admin
            .post(
                "/api/v1/customers",
                """
                {"name":"City Clinic","phone":"+255700000001","type":"WHOLESALE",
                 "creditLimit":50000,"paymentTerms":"NET_30"}
                """)
            .expect(201)
            .string("$.data.uid");
  }

  // ------------------------------------------------------------------ MON-3 refund methods

  @Test
  @DisplayName("MON-3: a walk-in cash sale is refunded in cash only")
  void walkInCashSaleRefundsInCash() {
    Response sale = sell(till, null, product, 1, "{\"method\":\"CASH\",\"amount\":10000}");
    for (String method : List.of("MOBILE_MONEY", "CARD", "CREDIT", "ACCOUNT_CREDIT", "VOUCHER")) {
      assertThat(returnOf(sale, 1, method).expect(422).string("$.reason"))
          .as(method)
          .isEqualTo("refund_method_not_allowed");
    }
    assertThat(returnOf(sale, 1, "INSURANCE").expect(422).string("$.reason"))
        .isEqualTo("refund_method_not_allowed");
    Response refund = returnOf(sale, 1, "CASH").expect(201);
    assertThat(refund.string("$.data.refunds[0].method")).isEqualTo("CASH");
    assertThat(money(refund, "$.data.refunds[0].amount")).isEqualTo(10_000.0);
  }

  @Test
  @DisplayName("MON-3: a cash + mobile money sale refunds each share its own way; the books agree")
  void splitTenderRefundsEachShareItsWay() {
    Response sale =
        sell(
            till,
            null,
            product,
            2,
            "{\"method\":\"CASH\",\"amount\":8000}",
            "{\"method\":\"MOBILE_MONEY\",\"channel\":\"MOBILE_OTHER\",\"amount\":12000}");
    // One unit back, no method named: 40% in cash, 60% by mobile money.
    Response first = returnOf(sale, 1, null).expect(201);
    assertThat(shares(first)).containsEntry("CASH", 4_000.0).containsEntry("MOBILE_MONEY", 6_000.0);
    assertThat(first.string("$.data.refundMethod")).isEqualTo("MOBILE_MONEY");
    // The second unit, the cashier naming cash: still split as paid.
    Response last = returnOf(sale, 1, "CASH").expect(201);
    assertThat(shares(last)).containsEntry("CASH", 4_000.0).containsEntry("MOBILE_MONEY", 6_000.0);

    String number = first.string("$.data.number");
    Api.eventually(
        () -> credited(number, "1020") == 6_000.0 && credited(number, "1000") == 4_000.0);
    assertThat(debited(number, "4100")).isEqualTo(10_000.0);
    assertBalanced();
  }

  @Test
  @DisplayName("MON-3: a credit sale's share goes back to the account, the cash share in cash")
  void creditShareGoesToTheAccount() {
    Response sale =
        sell(
            till,
            "\"customerUid\":\"%s\",\"balanceToAccount\":true".formatted(clinic),
            product,
            1,
            "{\"method\":\"CASH\",\"amount\":4000}");
    assertThat(balance(clinic)).isEqualTo(6_000.0);
    Response refund = returnOf(sale, 1, "CASH").expect(201);
    assertThat(shares(refund)).containsEntry("CASH", 4_000.0).containsEntry("CREDIT", 6_000.0);
    assertThat(balance(clinic)).isZero();
    String number = refund.string("$.data.number");
    Api.eventually(
        () -> credited(number, "1100") == 6_000.0 && credited(number, "1000") == 4_000.0);
    assertBalanced();
    // The ledger's receivables and the customer's account say the same.
    Api.eventually(() -> ledgerBalance("1100") == balance(clinic));
  }

  @Test
  @DisplayName("MON-3: a voucher's share is credited back onto the voucher")
  void voucherShareGoesBackOntoTheVoucher() {
    String voucher =
        till.post("/api/v1/vouchers", "{\"value\":5000,\"paidBy\":\"CASH\"}")
            .expect(201)
            .string("$.data.code");
    Response sale =
        sell(
            till,
            null,
            product,
            1,
            "{\"method\":\"VOUCHER\",\"amount\":5000,\"reference\":\"%s\"}".formatted(voucher),
            "{\"method\":\"CASH\",\"amount\":5000}");
    assertThat(voucherOf(voucher, "$.status")).isEqualTo("SPENT");
    Response refund = returnOf(sale, 1, "VOUCHER").expect(201);
    assertThat(shares(refund)).containsEntry("VOUCHER", 5_000.0).containsEntry("CASH", 5_000.0);
    assertThat(voucherOf(voucher, "$.status")).isEqualTo("ACTIVE");
    assertThat(Double.parseDouble(voucherOf(voucher, "$.balance"))).isEqualTo(5_000.0);
    String number = refund.string("$.data.number");
    Api.eventually(() -> credited(number, "2300") == 5_000.0);
    assertBalanced();
  }

  @Test
  @DisplayName("MON-3/MON-7/MON-15: insured sale: quote priced as the sale, member checked, refund")
  void insuredSale() {
    String patient = patient("Neema Kileo", "+255700000002");
    String other = patient("Juma Ally", "+255700000003");
    String member = member(patient, "NHIF-1");
    // The branch sells the medicine at 8,000, not the catalogue's 10,000.
    admin
        .put(
            "/api/v1/branch-prices",
            "{\"productUid\":\"%s\",\"branchUid\":\"%s\",\"price\":8000}"
                .formatted(product, branchUid))
        .expect(200);

    Response quote =
        till.post(
                "/api/v1/pos/coverage-quote",
                """
                {"memberUid":"%s","customerUid":"%s","lines":[{"productUid":"%s","quantity":1}]}
                """
                    .formatted(member, patient, product))
            .expect(200);
    assertThat(money(quote, "$.data.covered")).isEqualTo(7_200.0);
    assertThat(money(quote, "$.data.patientPays")).isEqualTo(800.0);
    // Even without the customer the quote takes the branch price.
    Response bare =
        till.post(
                "/api/v1/pos/coverage-quote",
                "{\"memberUid\":\"%s\",\"lines\":[{\"productUid\":\"%s\",\"quantity\":1}]}"
                    .formatted(member, product))
            .expect(200);
    assertThat(money(bare, "$.data.covered")).isEqualTo(7_200.0);
    assertThat(
            till.post(
                    "/api/v1/pos/coverage-quote",
                    """
                    {"memberUid":"%s","customerUid":"%s","lines":[{"productUid":"%s","quantity":1}]}
                    """
                        .formatted(member, other, product))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("member_not_of_customer");

    // MON-7: another customer cannot be sold on this member's insurance.
    Response stranger =
        sellRaw(
            till,
            "\"customerUid\":\"%s\",\"memberUid\":\"%s\"".formatted(other, member),
            product,
            1,
            "{\"method\":\"CASH\",\"amount\":800}");
    assertThat(stranger.expect(422).string("$.reason")).isEqualTo("member_not_of_customer");

    Response sale =
        sell(
            till,
            "\"customerUid\":\"%s\",\"memberUid\":\"%s\"".formatted(patient, member),
            product,
            1,
            "{\"method\":\"CASH\",\"amount\":800}");
    assertThat(money(sale, "$.data.total")).isEqualTo(8_000.0);

    // The insurer's share is never refunded to the patient; it comes off the claim.
    assertThat(returnOf(sale, 1, "INSURANCE").expect(422).string("$.reason"))
        .isEqualTo("refund_method_not_allowed");
    assertThat(returnOf(sale, 1, "MOBILE_MONEY").expect(422).string("$.reason"))
        .isEqualTo("refund_method_not_allowed");
    Response refund = returnOf(sale, 1, "CASH").expect(201);
    assertThat(shares(refund)).containsEntry("INSURANCE", 7_200.0).containsEntry("CASH", 800.0);
    String number = refund.string("$.data.number");
    Api.eventually(() -> credited(number, "1110") == 7_200.0 && credited(number, "1000") == 800.0);
    assertBalanced();
  }

  // ------------------------------------------------------------------ MON-11 credit limit race

  @Test
  @DisplayName("MON-11: two credit sales at once at two branches cannot pass the credit limit")
  void creditLimitHoldsUnderConcurrency() throws Exception {
    stock(second, product, 100);
    // Limit 50,000: each sale is 30,000 on account; only one may go through.
    List<Callable<Response>> sales = new ArrayList<>();
    for (Api at : List.of(till, second)) {
      sales.add(
          () ->
              sellRaw(
                  at,
                  "\"customerUid\":\"%s\",\"balanceToAccount\":true".formatted(clinic),
                  product,
                  3));
    }
    List<Response> results = all(sales);
    assertThat(results).extracting(Response::status).containsExactlyInAnyOrder(201, 422);
    Response refused = results.stream().filter(r -> r.status() == 422).findFirst().orElseThrow();
    assertThat(refused.string("$.reason")).isEqualTo("credit_limit_exceeded");
    assertThat(balance(clinic)).isEqualTo(30_000.0);
  }

  // ------------------------------------------------------------------ MON-12 lock order

  @Test
  @DisplayName("MON-12: tills selling [A, B] and [B, A] at once never fail with a 500")
  void oppositeOrderSalesNeverDeadlock() throws Exception {
    String other = product("Paracetamol 500mg", "1000");
    stock(till, other, 500);
    stock(second, product, 500);
    stock(second, other, 500);
    List<Callable<Response>> sales = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      boolean forward = i % 2 == 0;
      Api at = forward ? till : second;
      String a = forward ? product : other;
      String b = forward ? other : product;
      sales.add(
          () ->
              at.post(
                  "/api/v1/sales",
                  """
                  {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1},
                   {"productUid":"%s","quantity":1}],"payments":[{"method":"CASH","amount":20000}]}
                  """
                      .formatted(UUID.randomUUID(), a, b)));
    }
    List<Response> results = all(sales);
    assertThat(results)
        .allSatisfy(r -> assertThat(r.status()).as(r.body()).isIn(201, 409))
        .filteredOn(r -> r.status() == 409)
        .allSatisfy(r -> assertThat(r.string("$.reason")).isEqualTo("busy_try_again"));
    assertThat(results).filteredOn(r -> r.status() == 201).hasSizeGreaterThanOrEqualTo(10);
  }

  // ------------------------------------------------------------------ MON-16 push excess

  @Test
  @DisplayName("MON-16: a confirmed push payment over the total leaves its excess on account")
  void pushPaymentExcessToAccount() {
    root.put(
            "/api/v1/platform/payment-methods",
            """
            {"code":"SIMULATOR","name":"Test push payment (simulator)","kind":"MOBILE_MONEY",
             "adapter":"SIMULATOR","requiresReference":true,"enabled":true,"sortOrder":90}
            """)
        .expect(200);
    admin
        .put("/api/v1/payment-methods", "{\"methodCode\":\"SIMULATOR\",\"enabled\":true}")
        .expect(200);
    String intent =
        till.post(
                "/api/v1/pos/payment-requests",
                """
                {"methodCode":"SIMULATOR","amount":12000,"currency":"TZS","phone":"+255754000111"}
                """)
            .expect(201)
            .string("$.data.uid");
    Response sale =
        sell(
            till,
            "\"customerUid\":\"%s\",\"excessToAccount\":true".formatted(clinic),
            product,
            1,
            "{\"method\":\"MOBILE_MONEY\",\"channel\":\"SIMULATOR\",\"amount\":12000,\"reference\":\"%s\"}"
                .formatted(intent));
    assertThat(money(sale, "$.data.payments[0].amount")).isEqualTo(10_000.0);
    assertThat(balance(clinic)).isEqualTo(-2_000.0);
  }

  // ------------------------------------------------------------------ MON-17 rounding

  @Test
  @DisplayName(
      "MON-17: a 10.00 line of 3 units returns as 3.33, 3.33 and 3.34; 10.01 as 3.34, 3.34, 3.33")
  void lastReturnTakesTheRemainder() {
    // 3 × 3.34 = 10.02, less a 0.2% discount (0.02) = a 10.00 line; less 0.1% (0.01) = 10.01.
    // Both items are stocked before the first sale: opening stock is for setting up (FRD-11).
    String firstItem = stockedItem("3.34");
    String secondItem = stockedItem("3.34");
    List<Double> ten = threeReturns(firstItem, "0.2");
    assertThat(ten).containsExactly(3.33, 3.33, 3.34);
    List<Double> tenOhOne = threeReturns(secondItem, "0.1");
    assertThat(tenOhOne).containsExactly(3.34, 3.34, 3.33);
  }

  private String stockedItem(String price) {
    String item = product("Item " + UUID.randomUUID().toString().substring(0, 6), price);
    stock(till, item, 10);
    return item;
  }

  /** Sells 3 units of {@code item} with {@code discount}%, then returns them one by one. */
  private List<Double> threeReturns(String item, String discount) {
    Response sale =
        till.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","discountPercent":%s,
                 "lines":[{"productUid":"%s","quantity":3}],
                 "payments":[{"method":"CASH","amount":100}]}
                """
                    .formatted(UUID.randomUUID(), discount, item))
            .expect(201);
    List<Double> refunds = new ArrayList<>();
    for (int i = 0; i < 3; i++) {
      refunds.add(money(returnOf(sale, 1, "CASH").expect(201), "$.data.refundTotal"));
    }
    return refunds;
  }

  // ------------------------------------------------------------------ MON-20 local day

  @Test
  @DisplayName("MON-20: a sale at 01:30 in Dar es Salaam is listed on that local day")
  void salesListCutsDaysAtLocalMidnight() throws SQLException {
    Response sale = sell(till, null, product, 1, "{\"method\":\"CASH\",\"amount\":10000}");
    // 2026-03-11 01:30 in Dar es Salaam (UTC+3) is 2026-03-10 22:30 UTC.
    Instant at = Instant.parse("2026-03-10T22:30:00Z");
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement s =
          c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        s.setString(1, tenantUid);
        s.execute();
      }
      try (PreparedStatement s =
          c.prepareStatement("update sales.sales set created_at = ? where uid = ?::uuid")) {
        s.setTimestamp(1, Timestamp.from(at));
        s.setString(2, sale.string("$.data.uid"));
        assertThat(s.executeUpdate()).isEqualTo(1);
      }
      c.commit();
    }
    String number = sale.string("$.data.number");
    assertThat(listed("2026-03-11")).contains(number);
    assertThat(listed("2026-03-10")).doesNotContain(number);
  }

  private List<String> listed(String day) {
    return till.get("/api/v1/sales?from=%s&to=%s&limit=100".formatted(day, day))
        .expect(200)
        .json()
        .read("$.data.items[*].number");
  }

  // ------------------------------------------------------------------ helpers

  private String product(String name, String price) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"CAPSULE","schedule":"OTC",
             "sellingPrice":%s,"costPrice":1,"taxRatePercent":0,"storage":"ROOM_TEMPERATURE"}
            """
                .formatted(name, name, price))
        .expect(201)
        .string("$.data.uid");
  }

  private static void stock(Api at, String product, int quantity) {
    at.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"B-%s","expiryDate":"%s","quantity":%d,"unitCost":1}
            """
                .formatted(
                    product,
                    UUID.randomUUID().toString().substring(0, 6),
                    LocalDate.now().plusYears(2),
                    quantity))
        .expect(201);
  }

  private String patient(String name, String phone) {
    return admin
        .post(
            "/api/v1/customers",
            "{\"name\":\"%s\",\"phone\":\"%s\",\"type\":\"INSURANCE\"}".formatted(name, phone))
        .expect(201)
        .string("$.data.uid");
  }

  private String member(String customer, String number) {
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
    return admin
        .post(
            "/api/v1/insurance/members",
            """
            {"customerUid":"%s","schemeUid":"%s","memberNumber":"%s","principalName":"Neema",
             "validFrom":"%s","validUntil":"%s"}
            """
                .formatted(
                    customer,
                    scheme,
                    number,
                    LocalDate.now().minusDays(1),
                    LocalDate.now().plusYears(1)))
        .expect(201)
        .string("$.data.uid");
  }

  private static Response sell(
      Api at, String extra, String product, int quantity, String... payments) {
    return sellRaw(at, extra, product, quantity, payments).expect(201);
  }

  private static Response sellRaw(
      Api at, String extra, String product, int quantity, String... payments) {
    return at.post(
        "/api/v1/sales",
        """
        {"idempotencyKey":"%s",%s"lines":[{"productUid":"%s","quantity":%d}],"payments":[%s]}
        """
            .formatted(
                UUID.randomUUID(),
                extra == null ? "" : extra + ",",
                product,
                quantity,
                String.join(",", payments)));
  }

  private Response returnOf(Response sale, int quantity, String method) {
    return till.post(
        "/api/v1/sales/" + sale.string("$.data.uid") + "/returns",
        """
        {"reason":"Changed mind",%s"lines":[{"saleLineUid":"%s","quantity":%d}]}
        """
            .formatted(
                method == null ? "" : "\"refundMethod\":\"" + method + "\",",
                sale.string("$.data.lines[0].uid"),
                quantity));
  }

  private static Map<String, Double> shares(Response refund) {
    List<Map<String, Object>> refunds = refund.json().read("$.data.refunds");
    Map<String, Double> byMethod = new java.util.LinkedHashMap<>();
    refunds.forEach(
        r -> byMethod.merge((String) r.get("method"), number(r.get("amount")), Double::sum));
    return byMethod;
  }

  private String voucherOf(String code, String field) {
    List<Object> values =
        admin
            .get("/api/v1/vouchers")
            .expect(200)
            .json()
            .read("$.data.items[?(@.code == '%s')]%s".formatted(code, field.substring(1)));
    return String.valueOf(values.getFirst());
  }

  private double balance(String customer) {
    return money(
        admin.get("/api/v1/receivables/accounts/" + customer).expect(200), "$.data.balance");
  }

  private double credited(String reference, String account) {
    return journalSum(reference, account, "credit");
  }

  private double debited(String reference, String account) {
    return journalSum(reference, account, "debit");
  }

  private double journalSum(String reference, String account, String side) {
    List<Object> values =
        admin
            .get("/api/v1/journal?limit=100")
            .expect(200)
            .json()
            .read(
                "$.data.items[?(@.sourceReference == '%s')].lines[?(@.accountCode == '%s')].%s"
                    .formatted(reference, account, side));
    return values.stream().mapToDouble(MoneyCorrectnessSalesTest::number).sum();
  }

  /** The ledger balance of an asset account: debits less credits over the whole journal. */
  private double ledgerBalance(String account) {
    List<Object> debits =
        admin
            .get("/api/v1/journal?limit=100")
            .expect(200)
            .json()
            .read("$.data.items[*].lines[?(@.accountCode == '%s')].debit".formatted(account));
    List<Object> credits =
        admin
            .get("/api/v1/journal?limit=100")
            .expect(200)
            .json()
            .read("$.data.items[*].lines[?(@.accountCode == '%s')].credit".formatted(account));
    return debits.stream().mapToDouble(MoneyCorrectnessSalesTest::number).sum()
        - credits.stream().mapToDouble(MoneyCorrectnessSalesTest::number).sum();
  }

  /** Every journal entry balances. */
  private void assertBalanced() {
    List<Map<String, Object>> entries =
        admin.get("/api/v1/journal?limit=100").expect(200).json().read("$.data.items");
    for (Map<String, Object> entry : entries) {
      @SuppressWarnings("unchecked")
      List<Map<String, Object>> lines = (List<Map<String, Object>>) entry.get("lines");
      double debit = lines.stream().mapToDouble(l -> number(l.get("debit"))).sum();
      double credit = lines.stream().mapToDouble(l -> number(l.get("credit"))).sum();
      assertThat(Math.round(debit * 100))
          .as(String.valueOf(entry))
          .isEqualTo(Math.round(credit * 100));
    }
  }

  private static List<Response> all(List<Callable<Response>> calls) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(calls.size());
    try {
      List<Future<Response>> futures = pool.invokeAll(calls);
      List<Response> results = new ArrayList<>();
      for (Future<Response> future : futures) {
        results.add(future.get());
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  private static double money(Response response, String path) {
    return number(response.json().read(path));
  }

  private static double number(Object value) {
    if (value == null) {
      return 0;
    }
    return value instanceof Number n ? n.doubleValue() : Double.parseDouble(value.toString());
  }
}
