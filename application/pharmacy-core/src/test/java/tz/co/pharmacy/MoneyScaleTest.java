package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.platform.kernel.Amounts;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Money rounds to the pharmacy's currency (adversarial review STD-17): ISO 4217 minor units,
 * half-up ({@link Amounts#ROUNDING}). A Ugandan pharmacy (UGX, no minor unit) end to end over HTTP:
 * the costs of its stock, a sale with VAT taken out of the shelf price, change, a return and the
 * books are all whole shillings, and the books still match the stock at cost.
 */
class MoneyScaleTest extends IntegrationTest {

  private Api root;
  private Api admin;
  private Api till;
  private String supplier;

  @BeforeEach
  void ugandanPharmacy() {
    Api anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "UG" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post("/api/v1/platform/tenants", registration(code, email, "UG", "UGX"))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenant, branch);
    supplier =
        admin
            .post(
                "/api/v1/suppliers", "{\"name\":\"Kampala Drug Stores\",\"paymentTerms\":\"COD\"}")
            .expect(201)
            .string("$.data.uid");
  }

  @Test
  @DisplayName("ISO 4217 minor units, half-up: UGX, RWF and JPY 0; TZS, KES and USD 2; KWD 3")
  void minorUnits() {
    assertThat(Amounts.minorUnits("UGX")).isZero();
    assertThat(Amounts.minorUnits("RWF")).isZero();
    assertThat(Amounts.minorUnits("JPY")).isZero();
    assertThat(Amounts.minorUnits("TZS")).isEqualTo(2);
    assertThat(Amounts.minorUnits("KES")).isEqualTo(2);
    assertThat(Amounts.minorUnits("USD")).isEqualTo(2);
    assertThat(Amounts.minorUnits("KWD")).isEqualTo(3);
    assertThat(Amounts.money(new BigDecimal("482.50"), "UGX")).isEqualByComparingTo("483");
    assertThat(Amounts.money(new BigDecimal("-482.50"), "UGX")).isEqualByComparingTo("-483");
    assertThat(Amounts.money(new BigDecimal("0.125"), "TZS")).isEqualByComparingTo("0.13");
    assertThat(Amounts.money(new BigDecimal("0.0125"), "KWD")).isEqualByComparingTo("0.013");
    assertThat(Amounts.display("UGX", new BigDecimal("240000.4"))).isEqualTo("UGX 240,000");
    assertThat(Amounts.display("TZS", new BigDecimal("240000"))).isEqualTo("TZS 240,000.00");

    // The money columns keep 2 decimals: a 3-decimal base currency is refused.
    String code = "KW" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    Response kwd =
        root.post(
            "/api/v1/platform/tenants",
            registration(code, code.toLowerCase() + "@test.local", "KW", "KWD"));
    assertThat(kwd.expect(422).string("$.reason")).isEqualTo("currency_not_supported");
    assertThat(kwd.string("$.field")).isEqualTo("currency");
  }

  @Test
  @DisplayName("a UGX pharmacy: stock costs, a sale, change, a return and the books in whole UGX")
  void ugandanShillingsEndToEnd() {
    String panadol =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Panadol 500mg","genericName":"Paracetamol","dosageForm":"TABLET",
                 "schedule":"OTC","sellingPrice":1055,"costPrice":100,"taxRatePercent":18}
                """)
            .expect(201)
            .string("$.data.uid");

    // Two receipts of one batch at 100 and 101 a unit: (300 + 404) / 7 = 100.57 → 101 shillings,
    // whole; the stock is worth 707, 3 more than was paid, and the books post the 3.
    receive(panadol, "PAN-2611", 3, 100, "UG-INV-1");
    receive(panadol, "PAN-2611", 4, 101, "UG-INV-2");
    assertThat(batchCost(panadol)).isEqualTo(101.0);
    assertThat(stockAtCost()).isEqualTo(707.0);
    eventually(() -> Math.abs(ledger("1200") - 707.0) < 0.001);

    // Three at 1,055 with 18% VAT in the price: the VAT is 3,165 × 18 / 118 = 482.80 → 483
    // (a TZS pharmacy keeps 482.80). Paid 3,200 in cash: change 35.
    Response sale =
        till.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":3}],
                 "payments":[{"method":"CASH","amount":3200}]}
                """
                    .formatted(UUID.randomUUID(), panadol))
            .expect(201);
    assertThat(money(sale, "$.data.total")).isEqualTo(3165.0);
    assertThat(money(sale, "$.data.taxTotal")).isEqualTo(483.0);
    assertThat(money(sale, "$.data.paidTotal")).isEqualTo(3200.0);
    assertThat(money(sale, "$.data.changeDue")).isEqualTo(35.0);
    String saleNumber = sale.string("$.data.number");

    // One comes back: 1,055 refunded, its VAT whole too.
    Response refund =
        till.post(
                "/api/v1/sales/" + sale.string("$.data.uid") + "/returns",
                """
                {"reason":"Wrong strength","refundMethod":"CASH",
                 "lines":[{"saleLineUid":"%s","quantity":1}]}
                """
                    .formatted(sale.string("$.data.lines[0].uid")))
            .expect(201);
    assertThat(money(refund, "$.data.refundTotal")).isEqualTo(1055.0);
    assertWhole(refund.json().read("$.data..*"), "the return");
    String returnNumber = refund.string("$.data.number");

    // The books: both documents posted, every line in whole shillings, debits equal credits, the
    // stock at cost equals the inventory account, the VAT account holds the VAT kept.
    eventually(
        () -> {
          String journal = admin.get("/api/v1/journal?limit=100").expect(200).body();
          return journal.contains(saleNumber) && journal.contains(returnNumber);
        });
    Response journal = admin.get("/api/v1/journal?limit=100").expect(200);
    List<Object> debits = journal.json().read("$.data.items[*].lines[*].debit");
    List<Object> credits = journal.json().read("$.data.items[*].lines[*].credit");
    assertThat(debits).isNotEmpty();
    assertWhole(debits, "journal debits");
    assertWhole(credits, "journal credits");

    Response trial = admin.get("/api/v1/reports/trial-balance").expect(200);
    assertThat(money(trial, "$.data.totalDebit")).isEqualTo(money(trial, "$.data.totalCredit"));
    assertWhole(List.of(trial.json().read("$.data.totalDebit", Object.class)), "trial balance");

    assertThat(stockAtCost()).isEqualTo(5 * 101.0);
    eventually(() -> Math.abs(ledger("1200") - 5 * 101.0) < 0.001);
    double vatKept = ledger("2100");
    assertThat(vatKept).isEqualTo(Math.rint(vatKept)).isPositive().isLessThan(483.0);
    assertThat(admin.get("/api/v1/finance/reconciliation").expect(200).body())
        .doesNotContain(saleNumber)
        .doesNotContain(returnNumber);
  }

  private static String registration(String code, String email, String country, String currency) {
    return """
        {"code":"%s","name":"Scale %s","legalName":"Scale %s Ltd","country":"%s",
         "currency":"%s","timezone":"Africa/Kampala",
         "headOffice":{"code":"HQ","name":"Head Office"},
         "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
        """
        .formatted(code, code, code, country, currency, email);
  }

  private void receive(String product, String batch, int quantity, int unitCost, String invoice) {
    till.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"%s","lines":[
              {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%d}]}
            """
                .formatted(
                    supplier,
                    invoice,
                    product,
                    batch,
                    LocalDate.now().plusYears(2),
                    quantity,
                    unitCost))
        .expect(201);
  }

  private double batchCost(String product) {
    return Double.parseDouble(
        till.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[0].unitCost")
            .toString());
  }

  private double stockAtCost() {
    List<Object> values =
        till.get("/api/v1/stock?limit=100").expect(200).json().read("$.data.items[*].costValue");
    return values.stream().mapToDouble(v -> Double.parseDouble(v.toString())).sum();
  }

  private double ledger(String code) {
    List<Object> balance =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + code + "')].balance");
    return Double.parseDouble(balance.getFirst().toString());
  }

  /** Every number among {@code values} is a whole number of shillings. */
  private static void assertWhole(List<Object> values, String what) {
    for (Object value : values) {
      if (value instanceof Number number) {
        BigDecimal amount = new BigDecimal(number.toString());
        assertThat(amount.stripTrailingZeros().scale())
            .as("%s: %s is not whole shillings", what, amount)
            .isLessThanOrEqualTo(0);
      }
    }
  }

  private static double money(Response response, String path) {
    Object value = response.json().read(path);
    return value instanceof Number number
        ? number.doubleValue()
        : Double.parseDouble((String) value);
  }
}
