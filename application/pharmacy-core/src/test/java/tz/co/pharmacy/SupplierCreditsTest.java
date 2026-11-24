package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Batch 10, money: a supplier return waits for the supplier's credit note (FRD-16) and, credited,
 * lowers what is open on its goods receipt, so no payment settles it again (Batch 3 "still open");
 * a foreign receipt converts each amount once and posts the rounding (STD-17); a supplier's
 * statement is read a page at a time over a capped period (PERF-5). After every step the payables
 * sub-ledger (the supplier balances) equals its control account 2000, and the returns awaiting
 * credit equal 1160.
 */
class SupplierCreditsTest extends IntegrationTest {

  private static final String USD_RATE = "2567.123457";

  private Api admin;
  private Api till;
  private String supplier;
  private String product;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SC" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Credits %s","legalName":"Credits %s Ltd","country":"TZ",
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
    till = admin.in(tenant, branch);
    supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Kariakoo Pharma\",\"paymentTerms\":\"NET_30\"}")
            .expect(201)
            .string("$.data.uid");
    product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Paracetamol 500mg","genericName":"Paracetamol","dosageForm":"TABLET",
                 "schedule":"OTC","sellingPrice":200,"costPrice":100,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
  }

  @Test
  @DisplayName(
      "a return waits for the credit note; credited, it lowers the receipt's open amount and no"
          + " payment settles it again; sub-ledgers equal their control accounts throughout")
  void creditNoteLowersTheReceipt() {
    String receipt = receive("PCM-1", 10, "100", "INV-100");
    eventually(() -> account("2000") == 1_000.0);
    assertSubLedgers();

    Response returned = returnToSupplier(receipt, "PCM-1", 2).expect(201);
    String returnUid = returned.string("$.data.uid");
    assertThat(returned.string("$.data.status")).isEqualTo("PENDING_CREDIT");
    assertThat(returned.read("$.data.credit", Object.class)).isNull();
    // The stock is out; the value waits in 1160 and nothing is owed less yet.
    eventually(() -> account("1160") == 200.0);
    assertThat(account("2000")).isEqualTo(1_000.0);
    assertThat(account("1200")).isEqualTo(800.0);
    assertThat(balance("awaitingCredit")).isEqualTo(200.0);
    assertSubLedgers();
    assertThat(
            till.get("/api/v1/supplier-returns?status=PENDING_CREDIT")
                .expect(200)
                .read("$.data.items.length()", Integer.class))
        .isEqualTo(1);

    // The supplier credits 180 for the 200 of stock: 180 off what is owed and off the receipt,
    // the 20 the supplier kept is a stock loss.
    String credit = "{\"number\":\"CN-501\",\"date\":\"%s\",\"amount\":%s}";
    Response future =
        admin.post(
            "/api/v1/supplier-returns/" + returnUid + "/credit-note",
            credit.formatted(LocalDate.now().plusDays(3), 180));
    assertThat(future.expect(422).string("$.reason")).isEqualTo("future_date");
    Response credited =
        admin
            .post(
                "/api/v1/supplier-returns/" + returnUid + "/credit-note",
                credit.formatted(LocalDate.now(), 180))
            .expect(200);
    assertThat(credited.string("$.data.status")).isEqualTo("CREDITED");
    assertThat(credited.read("$.data.credit.amount", Double.class)).isEqualTo(180.0);
    assertThat(credited.read("$.data.credit.appliedToReceipt", Double.class)).isEqualTo(180.0);
    eventually(() -> account("1160") == 0.0);
    eventually(() -> account("2000") == 820.0);
    assertThat(account("5100")).isEqualTo(20.0);
    assertSubLedgers();

    // Once only, and the supplier's note once only.
    Response again =
        admin.post(
            "/api/v1/supplier-returns/" + returnUid + "/credit-note",
            credit.formatted(LocalDate.now(), 180));
    assertThat(again.expect(409).string("$.reason")).isEqualTo("return_already_credited");
    String second = returnToSupplier(receipt, "PCM-1", 1).expect(201).string("$.data.uid");
    Response duplicate =
        admin.post(
            "/api/v1/supplier-returns/" + second + "/credit-note",
            credit.formatted(LocalDate.now(), 100).replace("CN-501", "cn-501"));
    assertThat(duplicate.expect(422).string("$.reason")).isEqualTo("duplicate_credit_note");

    // The receipt has 820 open: paying the whole 1,000 against it is refused.
    Response overpaid = pay(1_000, receipt);
    assertThat(overpaid.expect(422).string("$.reason")).isEqualTo("overpayment");
    assertThat(overpaid.read("$.open", Double.class)).isEqualTo(820.0);
    pay(820, receipt).expect(201);
    eventually(() -> account("2000") == 0.0);
    assertSubLedgers();

    // The second return's credit note comes after the receipt is paid: it is credit on account.
    Response late =
        admin
            .post(
                "/api/v1/supplier-returns/" + second + "/credit-note",
                "{\"number\":\"CN-502\",\"amount\":100}")
            .expect(200);
    assertThat(late.read("$.data.credit.appliedToReceipt", Double.class)).isEqualTo(0.0);
    eventually(() -> account("2000") == -100.0);
    assertThat(balance("balance")).isEqualTo(-100.0);
    assertSubLedgers();
  }

  @Test
  @DisplayName(
      "a foreign receipt: the payable is its total converted once, the stock its lines, and the"
          + " rounding is posted; paying the whole total settles it to the last cent")
  void foreignReceiptRoundsOnce() {
    admin.put("/api/v1/settings", "{\"acceptedCurrencies\":[\"TZS\",\"USD\"]}").expect(200);
    admin
        .post(
            "/api/v1/fx/rates",
            "{\"base\":\"USD\",\"quote\":\"TZS\",\"rate\":%s,\"effectiveOn\":\"%s\"}"
                .formatted(USD_RATE, LocalDate.now()))
        .expect(201);
    // Three lines of USD 0.33 × 7 = 2.31 each: 2.31 × 2,567.123457 = 5,930.055… → 5,930.06 a
    // line, 17,790.18 the lines; the total 6.93 × rate = 17,790.165… → 17,790.17. The 0.01
    // between them is the conversion's rounding.
    String other = productNamed("Ibuprofen 200mg");
    String third = productNamed("Cetirizine 10mg");
    Response receipt =
        till.post(
                "/api/v1/goods-receipts",
                """
                {"supplierUid":"%s","supplierInvoiceNumber":"USD-1","currency":"USD",
                 "exchangeRate":%s,"lines":[
                  {"productUid":"%s","batchNumber":"F-1","expiryDate":"%s","quantity":7,"unitCost":0.33},
                  {"productUid":"%s","batchNumber":"F-2","expiryDate":"%s","quantity":7,"unitCost":0.33},
                  {"productUid":"%s","batchNumber":"F-3","expiryDate":"%s","quantity":7,"unitCost":0.33}]}
                """
                    .formatted(
                        supplier, USD_RATE, product, expiry(), other, expiry(), third, expiry()))
            .expect(201);
    BigDecimal rate = new BigDecimal(USD_RATE);
    BigDecimal payable = new BigDecimal("6.93").multiply(rate).setScale(2, RoundingMode.HALF_UP);
    BigDecimal line = new BigDecimal("2.31").multiply(rate).setScale(2, RoundingMode.HALF_UP);
    BigDecimal lines = line.multiply(BigDecimal.valueOf(3));
    assertThat(payable).isNotEqualByComparingTo(lines);
    assertThat(receipt.read("$.data.baseTotal", BigDecimal.class)).isEqualByComparingTo(payable);
    assertThat(receipt.read("$.data.lines[0].baseValue", BigDecimal.class))
        .isEqualByComparingTo(line);

    eventually(() -> account("2000") == payable.doubleValue());
    // The ledger's inventory is the stock at cost, to the cent; 7000 holds the rounding.
    // (Each batch keeps its cost to the cent: 5,930.06 / 7 → 847.15, and that rounding is posted
    // as before, MON-9.)
    eventually(() -> Math.abs(account("1200") - stockAtCost()) < 0.001);
    assertThat(account("7000")).isEqualTo(payable.subtract(lines).doubleValue());
    assertSubLedgers();

    // The whole USD total paid at the booked rate leaves nothing owed.
    admin
        .post(
            "/api/v1/finance/supplier-payments",
            """
            {"supplierUid":"%s","amount":6.93,"currency":"USD","rate":%s,
             "paymentMethod":"BANK_TRANSFER","goodsReceiptUids":["%s"]}
            """
                .formatted(supplier, USD_RATE, receipt.string("$.data.uid")))
        .expect(201);
    eventually(() -> account("2000") == 0.0);
    assertSubLedgers();
  }

  @Test
  @DisplayName(
      "a statement is read a page at a time with an opening balance and a running balance, over a"
          + " capped period")
  void statementPages() {
    for (int i = 1; i <= 5; i++) {
      receive("ST-" + i, 1, String.valueOf(100 * i), "ST-INV-" + i);
    }
    pay(50, null).expect(201);
    eventually(() -> account("2000") == 1_450.0);

    String path = "/api/v1/suppliers/" + supplier + "/statement";
    Response whole = admin.get(path).expect(200);
    assertThat(whole.read("$.data.lines.length()", Integer.class)).isEqualTo(6);
    assertThat(whole.read("$.data.opening", Double.class)).isEqualTo(0.0);
    assertThat(whole.read("$.data.balance", Double.class)).isEqualTo(1_450.0);
    assertThat(whole.read("$.data.nextCursor", Object.class)).isNull();

    List<Double> running = new ArrayList<>();
    List<String> numbers = new ArrayList<>();
    String cursor = null;
    int pages = 0;
    do {
      Response page =
          admin.get(path + "?limit=2" + (cursor == null ? "" : "&cursor=" + cursor)).expect(200);
      running.addAll(page.json().read("$.data.lines[*].balance"));
      numbers.addAll(page.json().read("$.data.lines[*].number"));
      assertThat(page.read("$.data.balance", Double.class)).isEqualTo(1_450.0);
      cursor = page.json().read("$.data.nextCursor");
      pages++;
    } while (cursor != null && pages < 10);
    assertThat(pages).isEqualTo(3);
    assertThat(numbers).doesNotHaveDuplicates().hasSize(6);
    assertThat(running).isEqualTo(whole.json().read("$.data.lines[*].balance"));

    // A period that starts tomorrow opens with everything before it and holds no lines.
    LocalDate tomorrow = LocalDate.now().plusDays(1);
    Response later =
        admin.get(path + "?from=" + tomorrow + "&to=" + tomorrow.plusDays(5)).expect(200);
    assertThat(later.read("$.data.opening", Double.class)).isEqualTo(1_450.0);
    assertThat(later.read("$.data.lines.length()", Integer.class)).isZero();

    Response tooLong =
        admin.get(path + "?from=" + LocalDate.now().minusYears(2) + "&to=" + LocalDate.now());
    assertThat(tooLong.expect(422).string("$.reason")).isEqualTo("period_too_long");
    assertThat(admin.get(path + "?cursor=nonsense").expect(422).string("$.reason"))
        .isEqualTo("invalid_cursor");
  }

  // ------------------------------------------------------------------ helpers

  /** Σ supplier balances = 2000 accounts payable; Σ awaiting credit = 1160. */
  private void assertSubLedgers() {
    eventually(
        () -> {
          List<Double> balances =
              admin.get("/api/v1/suppliers/balances").json().read("$.data[*].balance");
          List<Double> awaiting =
              admin.get("/api/v1/suppliers/balances").json().read("$.data[*].awaitingCredit");
          double payables = balances.stream().mapToDouble(Number::doubleValue).sum();
          double pending = awaiting.stream().mapToDouble(Number::doubleValue).sum();
          return Math.abs(payables - account("2000")) < 0.001
              && Math.abs(pending - account("1160")) < 0.001;
        });
  }

  /** One field of the (only) supplier's balance row. */
  private double balance(String field) {
    Object value =
        admin.get("/api/v1/suppliers/balances").expect(200).json().read("$.data[0]." + field);
    return Double.parseDouble(value.toString());
  }

  private String receive(String batch, int quantity, String unitCost, String invoice) {
    return till.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"%s","lines":[
              {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%s}]}
            """
                .formatted(supplier, invoice, product, batch, expiry(), quantity, unitCost))
        .expect(201)
        .string("$.data.uid");
  }

  private Response returnToSupplier(String receipt, String batchNumber, int quantity) {
    List<String> batches =
        till.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[?(@.batchNumber == '" + batchNumber + "')].uid");
    return till.post(
        "/api/v1/supplier-returns",
        """
        {"supplierUid":"%s","goodsReceiptUid":"%s","reason":"Short expiry",
         "lines":[{"productUid":"%s","batchUid":"%s","quantity":%d}]}
        """
            .formatted(supplier, receipt, product, batches.getFirst(), quantity));
  }

  private Response pay(double amount, String receipt) {
    return admin.post(
        "/api/v1/finance/supplier-payments",
        """
        {"supplierUid":"%s","amount":%s,"currency":"TZS","paymentMethod":"BANK_TRANSFER"%s}
        """
            .formatted(
                supplier,
                BigDecimal.valueOf(amount).toPlainString(),
                receipt == null ? "" : ",\"goodsReceiptUids\":[\"" + receipt + "\"]"));
  }

  private String productNamed(String name) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
             "sellingPrice":2000,"costPrice":800,"taxRatePercent":0}
            """
                .formatted(name, name))
        .expect(201)
        .string("$.data.uid");
  }

  private static LocalDate expiry() {
    return LocalDate.now().plusYears(2);
  }

  private double account(String code) {
    List<Object> balance =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + code + "')].balance");
    return balance.isEmpty() ? 0 : Double.parseDouble(balance.getFirst().toString());
  }

  private double stockAtCost() {
    List<Object> values =
        till.get("/api/v1/stock?limit=100").expect(200).json().read("$.data.items[*].costValue");
    return values.stream().mapToDouble(v -> Double.parseDouble(v.toString())).sum();
  }
}
