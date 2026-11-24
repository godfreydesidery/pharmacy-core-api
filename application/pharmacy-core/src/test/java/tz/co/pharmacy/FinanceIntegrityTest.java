package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Batch 3 money integrity (adversarial review MON-10, MON-13, MON-14, MON-18, MON-20 / STD-11,
 * STD-12): Idempotency-Key on money-moving POSTs, the reconciliation of documents against the
 * journal, payment methods limited to money accounts, supplier overpayment, and dates in the
 * pharmacy's own time zone.
 */
class FinanceIntegrityTest extends IntegrationTest {

  private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private Api admin;
  private Api till;
  private String tenantUid;
  private String email;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "FI" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    email = code.toLowerCase() + "@test.local";
    tenantUid =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Integrity %s","legalName":"Integrity %s Ltd","country":"TZ",
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
    till = admin.in(tenantUid, branch);
  }

  // ------------------------------------------------------------------ idempotency (MON-10)

  @Test
  @DisplayName("a retried POST with the same Idempotency-Key replays the first answer, once")
  void replaysTheFirstResponse() {
    String key = UUID.randomUUID().toString();
    String body = expense("Generator fuel " + key, 15_000, "CASH");
    Response first = till.post("/api/v1/expenses", body, "Idempotency-Key", key).expect(201);
    assertThat(first.header("Idempotent-Replayed")).isNull();
    Response again = till.post("/api/v1/expenses", body, "Idempotency-Key", key).expect(201);
    assertThat(again.header("Idempotent-Replayed")).isEqualTo("true");
    assertThat(again.string("$.data.uid")).isEqualTo(first.string("$.data.uid"));
    assertThat(again.string("$.data.number")).isEqualTo(first.string("$.data.number"));
    assertThat(expensesNamed("Generator fuel " + key)).isOne();

    // The same key with another body is a different request.
    Response reused =
        till.post(
            "/api/v1/expenses",
            expense("Generator fuel " + key, 16_000, "CASH"),
            "Idempotency-Key",
            key);
    assertThat(reused.expect(422).string("$.reason")).isEqualTo("idempotency_key_reused");
    assertThat(expensesNamed("Generator fuel " + key)).isOne();

    // No header: unchanged, every POST records.
    till.post("/api/v1/expenses", expense("Plain " + key, 100, "CASH")).expect(201);
    till.post("/api/v1/expenses", expense("Plain " + key, 100, "CASH")).expect(201);
    assertThat(expensesNamed("Plain " + key)).isEqualTo(2);

    // A key longer than 255 characters is refused.
    assertThat(
            till.post("/api/v1/expenses", body, "Idempotency-Key", "k".repeat(256))
                .expect(400)
                .string("$.reason"))
        .isEqualTo("invalid_idempotency_key");
  }

  @Test
  @DisplayName("a failed first request frees its key; one still running answers 409")
  void failureReleasesAndRunningConflicts() throws Exception {
    String key = UUID.randomUUID().toString();
    till.post("/api/v1/expenses", expense("Broken " + key, 0, "CASH"), "Idempotency-Key", key)
        .expect(422);
    till.post("/api/v1/expenses", expense("Fixed " + key, 500, "CASH"), "Idempotency-Key", key)
        .expect(201);
    assertThat(expensesNamed("Fixed " + key)).isOne();

    // A first request still running (its reservation is in the database, on any instance).
    String running = UUID.randomUUID().toString();
    String body = expense("Running " + running, 700, "CASH");
    owner(
        """
        insert into platform.idempotency_keys (tenant_uid, idempotency_key, request_method,
          request_path, fingerprint, user_uid, state, created_at, expires_at)
        select ?::uuid, ?, 'POST', '/api/v1/expenses', ?, uid, 'IN_PROGRESS', now(),
               now() + interval '1 day' from access.users where email = ?
        """,
        tenantUid,
        running,
        sha256(body),
        email);
    assertThat(
            till.post("/api/v1/expenses", body, "Idempotency-Key", running)
                .expect(409)
                .string("$.reason"))
        .isEqualTo("idempotency_in_progress");
    assertThat(expensesNamed("Running " + running)).isZero();
  }

  @Test
  @DisplayName("the same key sent at once from several tills records the payment once")
  void concurrentRetriesRecordOnce() throws Exception {
    String key = UUID.randomUUID().toString();
    String body = expense("Race " + key, 1_000, "MOBILE_MONEY");
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Callable<Response>> calls = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        calls.add(() -> till.post("/api/v1/expenses", body, "Idempotency-Key", key));
      }
      int recorded = 0;
      for (Future<Response> future : pool.invokeAll(calls)) {
        Response response = future.get();
        if (response.status() == 201 && response.header("Idempotent-Replayed") == null) {
          recorded++;
        } else if (response.status() == 201) {
          assertThat(response.header("Idempotent-Replayed")).isEqualTo("true");
        } else {
          assertThat(response.expect(409).string("$.reason")).isEqualTo("idempotency_in_progress");
        }
      }
      assertThat(recorded).isOne();
    } finally {
      pool.shutdownNow();
    }
    assertThat(expensesNamed("Race " + key)).isOne();
  }

  // ------------------------------------------------------------------ reconciliation (MON-13)

  @Test
  @DisplayName("the reconciliation lists a document whose journal entry is missing")
  void reconciliationFindsAMissingPosting() throws Exception {
    Response expense =
        till.post("/api/v1/expenses", expense("Unposted rent", 40_000, "BANK_TRANSFER"))
            .expect(201);
    String uid = expense.string("$.data.uid");
    String number = expense.string("$.data.number");
    assertThat(unposted()).doesNotContain(number);

    // An hour old, and its journal entry lost.
    owner(
        "update finance.expenses set created_at = now() - interval '1 hour' where uid = ?::uuid",
        uid);
    owner(
        "delete from finance.journal_lines where entry_id in (select id from finance.journal_entries"
            + " where source_uid = ?::uuid)",
        uid);
    owner("delete from finance.journal_entries where source_uid = ?::uuid", uid);

    Response report = admin.get("/api/v1/finance/reconciliation").expect(200);
    assertThat(report.json().read("$.data[*].number", List.class)).contains(number);
    assertThat(report.json().read("$.data[?(@.number == '" + number + "')].sourceType", List.class))
        .containsExactly("EXPENSE");
    assertThat(admin.get("/api/v1/finance/reconciliation?days=0").expect(422).string("$.reason"))
        .isEqualTo("invalid_days");
  }

  // ------------------------------------------------------------------ payment methods (MON-14)

  @Test
  @DisplayName("expenses and supplier payments are paid only from money accounts")
  void paymentMethodsAreMoneyAccounts() {
    for (String method : List.of("CREDIT", "INSURANCE", "VOUCHER", "CASHH")) {
      Response refused = till.post("/api/v1/expenses", expense("Odd", 100, method));
      assertThat(refused.expect(422).string("$.reason")).isEqualTo("invalid_payment_method");
      assertThat(refused.string("$.field")).isEqualTo("paymentMethod");
    }
    assertThat(
            till.post("/api/v1/expenses", expense("Bank fee", 100, "bank_transfer"))
                .expect(201)
                .string("$.data.paymentMethod"))
        .isEqualTo("BANK_TRANSFER");
    String supplier = supplier();
    assertThat(
            admin
                .post(
                    "/api/v1/finance/supplier-payments",
                    "{\"supplierUid\":\"%s\",\"amount\":100,\"paymentMethod\":\"CREDIT\"}"
                        .formatted(supplier))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_payment_method");
    // Nothing reached the receivables.
    for (String receivable : List.of("1100", "1110")) {
      List<Number> balance =
          admin
              .get("/api/v1/accounts")
              .expect(200)
              .json()
              .read("$.data[?(@.code == '" + receivable + "')].balance", List.class);
      assertThat(balance).allSatisfy(value -> assertThat(value.doubleValue()).isZero());
    }
  }

  // ------------------------------------------------------------------ overpayment (MON-18)

  @Test
  @DisplayName("a goods receipt cannot be paid beyond what is still open on it")
  void supplierOverpaymentRefused() {
    String supplier = supplier();
    String product = product();
    String receipt =
        till.post(
                "/api/v1/goods-receipts",
                """
                {"supplierUid":"%s","supplierInvoiceNumber":"INV-%s","lines":[
                  {"productUid":"%s","batchNumber":"B-1","expiryDate":"%s","quantity":10,
                   "unitCost":300}]}
                """
                    .formatted(supplier, UUID.randomUUID(), product, LocalDate.now().plusYears(1)))
            .expect(201)
            .string("$.data.uid");
    String payment =
        "{\"supplierUid\":\"%s\",\"amount\":%s,\"paymentMethod\":\"CASH\",\"goodsReceiptUids\":[\"%s\"]}";
    admin
        .post("/api/v1/finance/supplier-payments", payment.formatted(supplier, 2000, receipt))
        .expect(201);
    Response over =
        admin.post("/api/v1/finance/supplier-payments", payment.formatted(supplier, 1500, receipt));
    assertThat(over.expect(422).string("$.reason")).isEqualTo("overpayment");
    assertThat(over.string("$.detail")).contains("1000.00");
    admin
        .post("/api/v1/finance/supplier-payments", payment.formatted(supplier, 1000, receipt))
        .expect(201);
    assertThat(
            admin
                .post("/api/v1/finance/supplier-payments", payment.formatted(supplier, 1, receipt))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("overpayment");
    // On account (no receipt named) stays possible.
    admin
        .post(
            "/api/v1/finance/supplier-payments",
            "{\"supplierUid\":\"%s\",\"amount\":50,\"paymentMethod\":\"CASH\"}".formatted(supplier))
        .expect(201);
  }

  // ------------------------------------------------------------------ dates (MON-20, STD-12)

  @Test
  @DisplayName("expense and supplier payment dates may be today in the pharmacy, not later")
  void futureDatesRefused() {
    LocalDate today = LocalDate.now(DAR);
    Response future =
        till.post(
            "/api/v1/expenses",
            """
            {"category":"RENT","description":"Next month","amount":100,"paymentMethod":"CASH",
             "expenseDate":"%s"}
            """
                .formatted(today.plusDays(2)));
    assertThat(future.expect(422).string("$.reason")).isEqualTo("future_date");
    assertThat(future.string("$.field")).isEqualTo("expenseDate");
    till.post(
            "/api/v1/expenses",
            """
            {"category":"RENT","description":"Today","amount":100,"paymentMethod":"CASH",
             "expenseDate":"%s"}
            """
                .formatted(today))
        .expect(201);
    String supplier = supplier();
    Response payment =
        admin.post(
            "/api/v1/finance/supplier-payments",
            "{\"supplierUid\":\"%s\",\"amount\":100,\"paymentMethod\":\"CASH\",\"paidOn\":\"%s\"}"
                .formatted(supplier, today.plusDays(2)));
    assertThat(payment.expect(422).string("$.reason")).isEqualTo("future_date");
    assertThat(payment.string("$.field")).isEqualTo("paidOn");
    admin
        .post(
            "/api/v1/finance/supplier-payments",
            "{\"supplierUid\":\"%s\",\"amount\":100,\"paymentMethod\":\"CASH\",\"paidOn\":\"%s\"}"
                .formatted(supplier, today))
        .expect(201);
  }

  // ------------------------------------------------------------------ helpers

  private static String expense(String description, double amount, String method) {
    return """
        {"category":"UTILITIES","description":"%s","amount":%s,"paymentMethod":"%s"}
        """
        .formatted(description, amount, method);
  }

  private long expensesNamed(String description) {
    List<?> found =
        till.get("/api/v1/expenses?limit=100")
            .expect(200)
            .json()
            .read("$.data.items[?(@.description == '" + description + "')]", List.class);
    return found.size();
  }

  private List<String> unposted() {
    return admin.get("/api/v1/finance/reconciliation").expect(200).json().read("$.data[*].number");
  }

  private String supplier() {
    return admin
        .post(
            "/api/v1/suppliers",
            "{\"name\":\"Supplier %s\",\"paymentTerms\":\"COD\"}"
                .formatted(UUID.randomUUID().toString().substring(0, 6)))
        .expect(201)
        .string("$.data.uid");
  }

  private String product() {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"Paracetamol %s","genericName":"Paracetamol","dosageForm":"TABLET",
             "schedule":"OTC","sellingPrice":500,"costPrice":300,"taxRatePercent":0,
             "storage":"ROOM_TEMPERATURE"}
            """
                .formatted(UUID.randomUUID().toString().substring(0, 6)))
        .expect(201)
        .string("$.data.uid");
  }

  /**
   * A statement as the schema owner, in this pharmacy (row-level security is forced on the owner
   * too) and past the journal's append-only triggers, as the erasure is.
   */
  private void owner(String statement, Object... args) throws Exception {
    try (Connection c = DriverManager.getConnection(url, owner, ownerPassword)) {
      c.setAutoCommit(false);
      try (PreparedStatement scope =
          c.prepareStatement(
              "select set_config('pharmacy.tenant_uid', ?, true),"
                  + " set_config('pharmacy.erasing_tenant', ?, true)")) {
        scope.setString(1, tenantUid);
        scope.setString(2, tenantUid);
        try (ResultSet ignored = scope.executeQuery()) {
          // scoped
        }
      }
      try (PreparedStatement s = c.prepareStatement(statement)) {
        for (int i = 0; i < args.length; i++) {
          s.setObject(i + 1, args[i]);
        }
        s.execute();
      }
      c.commit();
    }
  }

  private static String sha256(String body) throws Exception {
    return HexFormat.of()
        .formatHex(
            MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
  }
}
