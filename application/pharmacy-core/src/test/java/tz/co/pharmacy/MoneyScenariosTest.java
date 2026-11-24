package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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
import tz.co.pharmacy.support.Staff;

/**
 * Every way a customer can pay, end to end over HTTP: partial payment with the balance on account,
 * full credit, mixed currencies and change, refusals, settlement in a foreign currency, deposits
 * spent at the till, an insured sale with co-pay and its claim, returns of a credit sale, and the
 * ledger catching up. One product at TZS 10,000 keeps the arithmetic readable.
 */
class MoneyScenariosTest extends IntegrationTest {

  private static final double PRICE = 10_000.0;
  private static final double USD = 2_650.0;

  @Autowired DataSource dataSource;

  private Api admin;
  private Api till;
  private Api anonymous;
  private String tenantUid;
  private String branchUid;
  private String product;
  private String clinic;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "MS" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Money %s","legalName":"Money %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenant, branch);
    tenantUid = tenant;
    branchUid = branch;

    admin.put("/api/v1/settings", "{\"acceptedCurrencies\":[\"TZS\",\"USD\"]}").expect(200);
    admin
        .post(
            "/api/v1/fx/rates",
            "{\"base\":\"USD\",\"quote\":\"TZS\",\"rate\":%s,\"effectiveOn\":\"%s\"}"
                .formatted(USD, LocalDate.now()))
        .expect(201);
    product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Amoxicillin 500mg","genericName":"Amoxicillin","dosageForm":"CAPSULE",
                 "schedule":"OTC","sellingPrice":%s,"costPrice":6000,"taxRatePercent":0,
                 "storage":"ROOM_TEMPERATURE"}
                """
                    .formatted(PRICE))
            .expect(201)
            .string("$.data.uid");
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"AMX-1","expiryDate":"%s","quantity":200,"unitCost":6000}
            """
                .formatted(product, LocalDate.now().plusYears(2)))
        .expect(201);
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

  @Test
  @DisplayName("partial payment puts the balance on account, which a USD payment later settles")
  void partialPaymentThenForeignSettlement() {
    Response sale =
        sell(
                "\"customerUid\":\"%s\",\"balanceToAccount\":true".formatted(clinic),
                payment("CASH", 4_000, null))
            .expect(201);
    assertThat(money(sale, "$.data.total")).isEqualTo(PRICE);
    assertThat(sale.json().read("$.data.payments[*].method", List.class)).contains("CREDIT");
    assertThat(balance()).isEqualTo(6_000.0);

    // The customer pays USD 2 (TZS 5,300) on account.
    admin
        .post(
            "/api/v1/receivables/payments",
            """
            {"customerUid":"%s","amount":2,"method":"CASH","reference":"USD-CASH","currency":"USD",
             "exchangeRate":%s}
            """
                .formatted(clinic, USD))
        .expect(201);
    assertThat(balance()).isEqualTo(700.0);
  }

  @Test
  @DisplayName("mixed currencies cover a sale; change is given in base cash")
  void mixedCurrencyTenderAndChange() {
    Response mixed =
        sell(
                null,
                payment("CASH", 2, "USD"),
                "{\"method\":\"MOBILE_MONEY\",\"channel\":\"MOBILE_OTHER\",\"amount\":4700}")
            .expect(201);
    assertThat(money(mixed, "$.data.paidTotal")).isEqualTo(PRICE);
    assertThat(money(mixed, "$.data.changeDue")).isZero();

    Response change = sell(null, payment("CASH", 5, "USD")).expect(201);
    assertThat(money(change, "$.data.changeDue")).isEqualTo(5 * USD - PRICE);
  }

  @Test
  @DisplayName("refusals: card overpayment, credit for a walk-in, credit over the limit, short pay")
  void refusals() {
    assertThat(sell(null, payment("CARD", 12_000, null)).expect(422).string("$.reason"))
        .isEqualTo("overpaid_non_cash");
    assertThat(sell(null, payment("CREDIT", PRICE, null)).expect(422).string("$.reason"))
        .isEqualTo("customer_required");
    assertThat(sell(null, payment("CASH", 9_000, null)).expect(422).string("$.reason"))
        .isEqualTo("payment_short");
    Response overLimit =
        sell(
            "\"customerUid\":\"%s\",\"balanceToAccount\":true".formatted(clinic), 6, new String[0]);
    assertThat(overLimit.expect(422).string("$.reason")).isEqualTo("credit_limit_exceeded");
    // Amounts read as money: currency code and thousands separators.
    assertThat(overLimit.string("$.detail")).contains("TZS 60,000.00").doesNotContain("60000");
  }

  @Test
  @DisplayName("a deposit on account is spent at the till as ACCOUNT_CREDIT")
  void depositSpentAtTheTill() {
    admin
        .post(
            "/api/v1/receivables/payments",
            "{\"customerUid\":\"%s\",\"amount\":4000,\"method\":\"MOBILE_MONEY\",\"reference\":\"DEP-1\"}"
                .formatted(clinic))
        .expect(201);
    assertThat(balance()).isEqualTo(-4_000.0);

    sell(
            "\"customerUid\":\"%s\"".formatted(clinic),
            payment("ACCOUNT_CREDIT", 4_000, null),
            payment("CASH", 6_000, null))
        .expect(201);
    assertThat(balance()).isZero();
  }

  @Test
  @DisplayName("an insured sale: the patient pays the 10% co-pay, the insurer's share is claimed")
  void insuredSaleRaisesAClaim() {
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
                "{\"payerUid\":\"%s\",\"name\":\"Standard\",\"copayType\":\"PERCENT\",\"copayValue\":10}"
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

    Response quote =
        till.post(
                "/api/v1/pos/coverage-quote",
                "{\"memberUid\":\"%s\",\"lines\":[{\"productUid\":\"%s\",\"quantity\":1}]}"
                    .formatted(member, product))
            .expect(200);
    assertThat(money(quote, "$.data.covered")).isEqualTo(9_000.0);
    assertThat(money(quote, "$.data.copay")).isEqualTo(1_000.0);

    Response sale =
        sell(
                "\"customerUid\":\"%s\",\"memberUid\":\"%s\",\"diagnosisCode\":\"B54\""
                    .formatted(patient, member),
                payment("CASH", 1_000, null))
            .expect(201);
    assertThat(sale.json().read("$.data.payments[*].method", List.class)).contains("INSURANCE");
    Api.eventually(
        () ->
            admin
                    .get("/api/v1/insurance/claims?payerUid=" + payer)
                    .expect(200)
                    .read("$.data.items.length()", Integer.class)
                == 1);
    Response claims = admin.get("/api/v1/insurance/claims?payerUid=" + payer).expect(200);
    assertThat(money(claims, "$.data.items[0].claimed")).isEqualTo(9_000.0);
  }

  @Test
  @DisplayName("returning a credit sale credits the account; the ledger posts receivables")
  void creditSaleReturn() {
    Response sale =
        sell("\"customerUid\":\"%s\",\"balanceToAccount\":true".formatted(clinic), new String[0])
            .expect(201);
    assertThat(balance()).isEqualTo(PRICE);
    String line = sale.string("$.data.lines[0].uid");
    till.post(
            "/api/v1/sales/" + sale.string("$.data.uid") + "/returns",
            """
            {"reason":"Wrong item","refundMethod":"CREDIT","lines":[{"saleLineUid":"%s","quantity":1}]}
            """
                .formatted(line))
        .expect(201);
    assertThat(balance()).isZero();

    Api.eventually(
        () -> admin.get("/api/v1/journal?limit=100").expect(200).body().contains("\"1100\""));
  }

  @Test
  @DisplayName("a USD sale refunded in USD after the rate moved: the loss goes to 7000")
  void foreignRefundRealisesTheExchangeDifference() {
    Response sale = sell(null, payment("CASH", 5, "USD")).expect(201);
    String line = sale.string("$.data.lines[0].uid");
    String saleUid = sale.string("$.data.uid");

    // Credit and insurance are base-currency only; a currency not tendered is refused.
    Response viaCredit =
        till.post("/api/v1/sales/" + saleUid + "/returns", returnBody(line, "CREDIT", "USD"));
    assertThat(viaCredit.expect(422).string("$.reason")).isEqualTo("foreign_refund_method");
    Response euro =
        till.post("/api/v1/sales/" + saleUid + "/returns", returnBody(line, "CASH", "EUR"));
    assertThat(euro.expect(422).string("$.reason")).isNotBlank();

    // The shilling weakens to 2,800 before the customer comes back.
    admin
        .post(
            "/api/v1/fx/rates",
            "{\"base\":\"USD\",\"quote\":\"TZS\",\"rate\":2800,\"effectiveOn\":\"%s\"}"
                .formatted(LocalDate.now()))
        .expect(201);
    Response refund =
        till.post("/api/v1/sales/" + saleUid + "/returns", returnBody(line, "CASH", "USD"))
            .expect(201);
    // USD 3.77 back (TZS 10,000 at the sale's 2,650); paying it out today costs TZS 10,556.
    assertThat(money(refund, "$.data.refundTotal")).isEqualTo(PRICE);
    assertThat(refund.string("$.data.refundCurrency")).isEqualTo("USD");
    assertThat(money(refund, "$.data.refundForeignAmount")).isEqualTo(3.77);
    assertThat(money(refund, "$.data.saleExchangeRate")).isEqualTo(USD);
    assertThat(money(refund, "$.data.refundExchangeRate")).isEqualTo(2_800.0);
    assertThat(money(refund, "$.data.fxDifference")).isEqualTo(556.0);

    String number = refund.string("$.data.number");
    Api.eventually(
        () -> admin.get("/api/v1/journal?limit=100").expect(200).body().contains(number));
    Response journal = admin.get("/api/v1/journal?limit=100").expect(200);
    List<Object> fx =
        journal
            .json()
            .read(
                "$.data.items[?(@.sourceReference == '%s')].lines[?(@.accountCode == '7000')].debit"
                    .formatted(number));
    assertThat(fx).hasSize(1);
    assertThat(Double.parseDouble(fx.getFirst().toString())).isEqualTo(556.0);
  }

  @Test
  @DisplayName("a typed rate far from the recorded one is refused; a plausible one stands")
  void implausibleRateRefused() {
    String supplier =
        admin
            .post(
                "/api/v1/suppliers", "{\"name\":\"Rate Check Supplier\",\"paymentTerms\":\"COD\"}")
            .expect(201)
            .string("$.data.uid");
    String order =
        """
        {"supplierUid":"%s","currency":"USD","exchangeRate":%s,
         "lines":[{"productUid":"%s","quantity":10,"unitCost":2}]}
        """;
    // USD is recorded at 2,650; a typed 1 (the old "TZS at rate 1" slip, the other way round).
    Response wrong = till.post("/api/v1/purchase-orders", order.formatted(supplier, 1, product));
    assertThat(wrong.expect(422).string("$.reason")).isEqualTo("rate_out_of_range");
    till.post("/api/v1/purchase-orders", order.formatted(supplier, 2700, product)).expect(201);

    // The POS asks for a customer's insurance; "none" is a normal answer, not a 404.
    Response none =
        admin.get("/api/v1/insurance/members/current?customerUid=" + clinic).expect(200);
    assertThat(none.json().read("$.data", Object.class)).isNull();
  }

  @Test
  @DisplayName("statements balance; cashier and stock-valuation reports reflect the trading")
  void statements() {
    sell(null, payment("CASH", 12_000, null)).expect(201);
    sell(null, payment("CASH", 5, "USD")).expect(201);
    Api.eventually(
        () ->
            admin
                    .get("/api/v1/reports/trial-balance")
                    .expect(200)
                    .read("$.data.totalDebit", Double.class)
                > 0);
    Response trial = admin.get("/api/v1/reports/trial-balance").expect(200);
    assertThat(money(trial, "$.data.totalDebit")).isEqualTo(money(trial, "$.data.totalCredit"));
    Response sheet = admin.get("/api/v1/reports/balance-sheet").expect(200);
    assertThat(sheet.read("$.data.balanced", Boolean.class)).isTrue();
    admin.get("/api/v1/reports/vat").expect(200);

    Response cashiers = till.get("/api/v1/reports/cashiers").expect(200);
    assertThat(cashiers.read("$.data[0].sales", Integer.class)).isEqualTo(2);
    // Cash to hand over: 12,000 + USD 5 (13,250) − change (2,000 + 3,250) = 20,000 = two sales.
    assertThat(money(cashiers, "$.data[0].cashToHandOver")).isEqualTo(2 * PRICE);
    // Taken by method shows cash kept (net of change); what was handed over is kept apart.
    assertThat(money(cashiers, "$.data[0].tenders.CASH")).isEqualTo(2 * PRICE);
    assertThat(money(cashiers, "$.data[0].cashTendered")).isEqualTo(12_000.0 + 5 * USD);

    Response stock = till.get("/api/v1/reports/stock-valuation").expect(200);
    assertThat(money(stock, "$.data[0].costValue")).isEqualTo(198 * 6_000.0);
  }

  @Test
  @DisplayName("a till shift: float, cash sale with change, close with a shortfall")
  void shift() {
    assertThat(till.get("/api/v1/shifts/current").expect(200).json().read("$.data", Object.class))
        .isNull();
    till.post("/api/v1/shifts/open", "{\"openingFloat\":50000}").expect(201);
    assertThat(
            till.post("/api/v1/shifts/open", "{\"openingFloat\":1}").expect(409).string("$.reason"))
        .isEqualTo("shift_open");
    sell(null, payment("CASH", 12_000, null)).expect(201);
    Response closed =
        till.post("/api/v1/shifts/close", "{\"countedCash\":59000,\"note\":\"Short 1,000\"}")
            .expect(200);
    // 50,000 float + 12,000 cash − 2,000 change = 60,000 expected; 59,000 counted.
    assertThat(money(closed, "$.data.expectedCash")).isEqualTo(60_000.0);
    assertThat(money(closed, "$.data.variance")).isEqualTo(-1_000.0);
    assertThat(closed.read("$.data.sales", Integer.class)).isEqualTo(1);
    assertThat(closed.string("$.data.status")).isEqualTo("CLOSED");
  }

  @Test
  @DisplayName("a supervisor's cash refund from the cashier's drawer counts in the cashier's shift")
  void supervisorRefundCountsInTheCashiersShift() {
    String email =
        Staff.login(admin, anonymous, "Zawadi", "CASHIER", "Staff#2026x-strong").signIn();
    Api cashier =
        anonymous.as(anonymous.signIn(email, "Staff#2026x-strong")).in(tenantUid, branchUid);
    cashier.post("/api/v1/shifts/open", "{\"openingFloat\":50000}").expect(201);
    Response sale =
        cashier
            .post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
                 "payments":[{"method":"CASH","amount":10000}]}
                """
                    .formatted(UUID.randomUUID(), product))
            .expect(201);
    // The admin (no shift of their own) refunds the sale in cash from the cashier's drawer.
    till.post(
            "/api/v1/sales/" + sale.string("$.data.uid") + "/returns",
            returnBody(sale.string("$.data.lines[0].uid"), "CASH", "TZS"))
        .expect(201);
    Response closed = cashier.post("/api/v1/shifts/close", "{\"countedCash\":50000}").expect(200);
    // 50,000 float + 10,000 cash − 10,000 refunded = 50,000 expected; nothing short.
    assertThat(money(closed, "$.data.cashRefunds")).isEqualTo(10_000.0);
    assertThat(money(closed, "$.data.expectedCash")).isEqualTo(50_000.0);
    assertThat(money(closed, "$.data.variance")).isEqualTo(0.0);

    // The cashier report: one row per person, by name, whether they sold or refunded.
    sell(null, payment("CASH", 10_000, null)).expect(201);
    Response report = admin.get("/api/v1/reports/cashiers").expect(200);
    List<String> people = report.json().read("$.data[*].cashier");
    assertThat(people).containsExactly("Admin", "Zawadi");
    assertThat(report.read("$.data[0].sales", Integer.class)).isEqualTo(1);
    assertThat(report.read("$.data[0].returns", Integer.class)).isEqualTo(1);
    assertThat(report.string("$.data[1].cashierReference")).isEqualTo(email);
  }

  @Test
  @DisplayName("a cashier whose email changes mid-shift keeps one shift and one report row")
  void emailChangeKeepsTheShift() {
    String before =
        Staff.login(admin, anonymous, "Neema", "CASHIER", "Staff#2026x-strong").signIn();
    String after = "kesho." + UUID.randomUUID().toString().substring(0, 6) + "@mail.test";
    Api cashier =
        anonymous.as(anonymous.signIn(before, "Staff#2026x-strong")).in(tenantUid, branchUid);
    cashier.post("/api/v1/shifts/open", "{\"openingFloat\":50000}").expect(201);
    String sale =
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
         "payments":[{"method":"CASH","amount":10000}]}
        """;
    cashier.post("/api/v1/sales", sale.formatted(UUID.randomUUID(), product)).expect(201);

    // The account's email changes; the same person carries on at the same till.
    new JdbcTemplate(dataSource)
        .update("update access.users set email = ? where email = ?", after, before);
    cashier.post("/api/v1/sales", sale.formatted(UUID.randomUUID(), product)).expect(201);
    assertThat(
            cashier
                .post("/api/v1/shifts/open", "{\"openingFloat\":1}")
                .expect(409)
                .string("$.reason"))
        .isEqualTo("shift_open");

    Response closed = cashier.post("/api/v1/shifts/close", "{\"countedCash\":70000}").expect(200);
    // 50,000 float + 2 × 10,000 cash, before and after the change.
    assertThat(closed.read("$.data.sales", Integer.class)).isEqualTo(2);
    assertThat(money(closed, "$.data.expectedCash")).isEqualTo(70_000.0);

    Response report = admin.get("/api/v1/reports/cashiers").expect(200);
    List<Integer> sales = report.json().read("$.data[?(@.cashier == 'Neema')].sales");
    assertThat(sales).containsExactly(2);
    List<String> references =
        report.json().read("$.data[?(@.cashier == 'Neema')].cashierReference");
    assertThat(references).containsExactly(after);
    List<String> uids = report.json().read("$.data[?(@.cashier == 'Neema')].cashierUid");
    assertThat(uids).hasSize(1).doesNotContainNull();
  }

  private static String returnBody(String saleLine, String method, String currency) {
    return """
        {"reason":"Changed mind","refundMethod":"%s","refundCurrency":"%s",
         "lines":[{"saleLineUid":"%s","quantity":1}]}
        """
        .formatted(method, currency, saleLine);
  }

  private Response sell(String extra, String... payments) {
    return sell(extra, 1, payments);
  }

  private Response sell(String extra, int quantity, String... payments) {
    String body =
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":%d}],"payments":[%s]%s}
        """
            .formatted(
                UUID.randomUUID(),
                product,
                quantity,
                String.join(",", payments),
                extra == null ? "" : "," + extra);
    return till.post("/api/v1/sales", body);
  }

  private static String payment(String method, double amount, String currency) {
    return currency == null
        ? "{\"method\":\"%s\",\"amount\":%s}".formatted(method, amount)
        : "{\"method\":\"%s\",\"amount\":%s,\"currency\":\"%s\"}"
            .formatted(method, amount, currency);
  }

  private double balance() {
    return money(admin.get("/api/v1/receivables/accounts/" + clinic).expect(200), "$.data.balance");
  }

  private static double money(Response response, String path) {
    Object value = response.json().read(path);
    return value instanceof Number number
        ? number.doubleValue()
        : Double.parseDouble((String) value);
  }
}
