package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
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
import tz.co.pharmacy.support.Staff;

/**
 * Batch 7 read models and bounded reads (adversarial review PERF-4, PERF-8, PERF-13, PERF-14,
 * PERF-17, PERF-18): the daily account balances always equal the journal, backdated and
 * period-locked postings included; the product stock rows always equal the movements and stock
 * levels; old redelivery markers are purged; journal numbers stay a gap-free series under
 * concurrency; customer statements page a period with the balance brought forward; report periods
 * are limited to a year.
 */
class ReadModelsTest extends IntegrationTest {

  private static final ZoneId DAR = ZoneId.of("Africa/Dar_es_Salaam");
  private static final String ADMIN_PASSWORD = "Admin#2026x";
  private static final String MANAGER_PASSWORD = "Manager#2026x";

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private Api anonymous;
  private Api root;
  private Api admin;
  private Api hq;
  private String tenant;
  private String hqUid;
  private LocalDate today;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "RM" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Read models %s","legalName":"Read models %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"%s"}}
                """
                    .formatted(code, code, code, email, ADMIN_PASSWORD))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, ADMIN_PASSWORD)).in(tenant, null);
    hqUid =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    hq = admin.in(tenant, hqUid);
    today = LocalDate.now(DAR);
  }

  // ------------------------------------------------------------------ PERF-4 daily balances

  @Test
  @DisplayName(
      "PERF-4: random postings, backdated and period-locked ones too: balances, trial balance and"
          + " VAT from the read model equal the journal, and the check finds a tampered row")
  void balancesEqualTheJournal() {
    Random random = new Random(20261123);
    // The opening balances, half a year back and of no branch.
    LocalDate opened = today.minusDays(190);
    admin
        .post(
            "/api/v1/opening-balances",
            "{\"asOf\":\"%s\",\"cash\":50000,\"bank\":250000}".formatted(opened))
        .expect(201);
    String supplier = supplier();
    String product = product("Amoxicillin 500mg", 1_000, 400, 18);
    receive(supplier, product, "AMX-1", 200, 400, today.plusYears(1));
    String customer = customer();
    String[] methods = {"CASH", "BANK_TRANSFER", "MOBILE_MONEY"};
    String[] categories = {"RENT", "UTILITIES", "TRANSPORT", "SUPPLIES", "OTHER"};
    int expenses = 0;
    int payments = 0;
    int sales = 0;
    for (int i = 0; i < 12; i++) {
      LocalDate on = today.minusDays(random.nextInt(200));
      hq.post(
              "/api/v1/expenses",
              """
              {"category":"%s","description":"Random %d","amount":%d,"paymentMethod":"%s",
               "expenseDate":"%s"}
              """
                  .formatted(
                      categories[random.nextInt(categories.length)],
                      i,
                      500 + random.nextInt(20_000),
                      methods[random.nextInt(methods.length)],
                      on))
          .expect(201);
      expenses++;
    }
    for (int i = 0; i < 4; i++) {
      admin
          .post(
              "/api/v1/receivables/payments",
              """
              {"customerUid":"%s","amount":%d,"method":"CASH","reference":"R-%d","date":"%s"}
              """
                  .formatted(customer, 100 + random.nextInt(5_000), i, today.minusDays(i * 40L)))
          .expect(201);
      payments++;
    }
    for (int i = 0; i < 5; i++) {
      sell(product, 1 + random.nextInt(4));
      sales++;
    }

    // The books are closed through 60 days ago: a person's backdated expense into them is refused;
    // one after them is booked on its own day.
    admin
        .put(
            "/api/v1/finance/period-lock",
            "{\"lockedThrough\":\"%s\"}".formatted(today.minusDays(60)))
        .expect(200);
    assertThat(
            hq.post(
                    "/api/v1/expenses",
                    """
                    {"category":"RENT","description":"Too late","amount":1000,
                     "paymentMethod":"CASH","expenseDate":"%s"}
                    """
                        .formatted(today.minusDays(100)))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("period_locked");
    LocalDate firstOpen = today.minusDays(59);
    hq.post(
            "/api/v1/expenses",
            """
            {"category":"RENT","description":"First open day","amount":4321,
             "paymentMethod":"BANK_TRANSFER","expenseDate":"%s"}
            """
                .formatted(firstOpen))
        .expect(201);
    expenses++;

    // Everything posted: the expenses and the opening at once, the rest by the event listeners.
    int expected = 1 + expenses + payments + sales + 1;
    eventually(() -> count("select count(*) from finance.journal_entries") == expected);

    // The backdated rows: the opening's day (no branch) and the first open day, model = journal.
    for (LocalDate day : List.of(opened, firstOpen)) {
      for (String code : List.of("1000", "1010", "3000", "6000")) {
        assertThat(
                decimal(
                    "select coalesce(sum(debit - credit), 0) from finance.account_daily_balances"
                        + " where account_code = ? and day = ?",
                    code,
                    day))
            .as("%s on %s", code, day)
            .isEqualByComparingTo(
                decimal(
                    "select coalesce(sum(l.debit - l.credit), 0) from finance.journal_lines l"
                        + " join finance.journal_entries e on e.id = l.entry_id"
                        + " where l.account_code = ? and e.entry_date = ?",
                    code,
                    day));
      }
    }
    assertThat(
            decimal(
                "select debit from finance.account_daily_balances where account_code = '1010'"
                    + " and day = ? and branch_uid is null",
                opened))
        .isEqualByComparingTo("250000");

    // Balances: every account against its journal lines.
    Map<String, BigDecimal[]> journal = journalSums(null);
    Response accounts = admin.get("/api/v1/accounts").expect(200);
    List<Map<String, Object>> rows = accounts.json().read("$.data");
    assertThat(rows).isNotEmpty();
    for (Map<String, Object> account : rows) {
      BigDecimal[] sums = journal.getOrDefault((String) account.get("code"), zeros());
      String type = (String) account.get("type");
      BigDecimal expectedBalance =
          type.equals("ASSET") || type.equals("EXPENSE")
              ? sums[0].subtract(sums[1])
              : sums[1].subtract(sums[0]);
      assertThat(new BigDecimal(account.get("balance").toString()))
          .as("balance of %s", account.get("code"))
          .isEqualByComparingTo(expectedBalance);
    }

    // The trial balance on a past day, from the read model, against the journal up to that day.
    for (LocalDate asOf :
        List.of(opened.minusDays(1), opened, today.minusDays(150), firstOpen, today)) {
      Map<String, BigDecimal[]> upTo = journalSums(asOf);
      Response trial = admin.get("/api/v1/reports/trial-balance?asOf=" + asOf).expect(200);
      List<Map<String, Object>> lines = trial.json().read("$.data.lines");
      for (Map<String, Object> line : lines) {
        BigDecimal[] sums = upTo.getOrDefault((String) line.get("code"), zeros());
        assertThat(new BigDecimal(line.get("debit").toString()))
            .as("debit of %s on %s", line.get("code"), asOf)
            .isEqualByComparingTo(sums[0]);
        assertThat(new BigDecimal(line.get("credit").toString()))
            .as("credit of %s on %s", line.get("code"), asOf)
            .isEqualByComparingTo(sums[1]);
      }
      assertThat(money(trial, "$.data.totalDebit")).isEqualTo(money(trial, "$.data.totalCredit"));
    }
    assertThat(
            admin
                .get("/api/v1/reports/balance-sheet")
                .expect(200)
                .read("$.data.balanced", Boolean.class))
        .isTrue();

    // VAT of this month and of the whole year against the journal's 2100 lines.
    LocalDate yearAgo = today.minusDays(365);
    Response vat = admin.get("/api/v1/reports/vat?from=" + yearAgo + "&to=" + today).expect(200);
    BigDecimal output =
        decimal(
            "select coalesce(sum(l.credit), 0) from finance.journal_lines l"
                + " join finance.journal_entries e on e.id = l.entry_id"
                + " where l.account_code = '2100' and e.entry_date between ? and ?",
            yearAgo,
            today);
    assertThat(output).isPositive();
    assertThat(decimalAt(vat, "$.data.outputVat")).isEqualByComparingTo(output);

    // The check: nothing on any day of the last year; a tampered row is reported, by day.
    assertThat(balanceMismatches(366)).isEmpty();
    sql(
        "update finance.account_daily_balances set debit = debit + 1"
            + " where account_code = '1010' and day = ? and branch_uid = ?",
        firstOpen,
        UUID.fromString(hqUid));
    List<Map<String, Object>> mismatches = balanceMismatches(90);
    assertThat(mismatches).hasSize(1);
    assertThat(mismatches.getFirst().get("accountCode")).isEqualTo("1010");
    assertThat(mismatches.getFirst().get("day")).isEqualTo(firstOpen.toString());
    assertThat(mismatches.getFirst().get("branchUid")).isEqualTo(hqUid);
    assertThat(
            new BigDecimal(mismatches.getFirst().get("readModelDebit").toString())
                .subtract(new BigDecimal(mismatches.getFirst().get("journalDebit").toString())))
        .isEqualByComparingTo("1");
    assertThat(
            admin
                .get("/api/v1/finance/reconciliation/balances?days=367")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_days");
    // The document reconciliation is unchanged by the read model.
    admin.get("/api/v1/finance/reconciliation").expect(200);
  }

  // ------------------------------------------------------------------ PERF-14 journal numbers

  @Test
  @DisplayName(
      "PERF-14: expenses posted at once from eight tills get one gap-free JV series per year, and"
          + " the balances still equal the journal")
  void journalNumbersUnderConcurrency() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(8);
    List<Callable<Response>> calls = new ArrayList<>();
    for (int i = 0; i < 32; i++) {
      int n = i;
      calls.add(
          () ->
              hq.post(
                  "/api/v1/expenses",
                  """
                  {"category":"SUPPLIES","description":"Concurrent %d","amount":%d,
                   "paymentMethod":"CASH"}
                  """
                      .formatted(n, 1_000 + n)));
    }
    try {
      for (Future<Response> future : pool.invokeAll(calls)) {
        future.get().expect(201);
      }
    } finally {
      pool.shutdownNow();
    }
    // One more, booked in last year: its own series.
    hq.post(
            "/api/v1/expenses",
            """
            {"category":"RENT","description":"Last year","amount":5000,"paymentMethod":"CASH",
             "expenseDate":"%s"}
            """
                .formatted(LocalDate.of(today.getYear() - 1, 12, 31)))
        .expect(201);

    List<String> numbers =
        strings(
            "select number from finance.journal_entries where entry_date >= ? order by number",
            LocalDate.of(today.getYear(), 1, 1));
    List<String> series = new ArrayList<>();
    for (int i = 1; i <= 32; i++) {
      series.add("JV-%d-%06d".formatted(today.getYear(), i));
    }
    assertThat(numbers).containsExactlyElementsOf(series);
    assertThat(
            strings(
                "select number from finance.journal_entries where entry_date < ?",
                LocalDate.of(today.getYear(), 1, 1)))
        .containsExactly("JV-%d-000001".formatted(today.getYear() - 1));

    BigDecimal paid = BigDecimal.valueOf(32 * 1_000 + (31 * 32) / 2 + 5_000);
    Response accounts = admin.get("/api/v1/accounts").expect(200);
    List<Object> cash = accounts.json().read("$.data[?(@.code == '1000')].balance");
    assertThat(new BigDecimal(cash.getFirst().toString())).isEqualByComparingTo(paid.negate());
    assertThat(balanceMismatches(366)).isEmpty();
  }

  // ------------------------------------------------------------------ PERF-8 product stock

  @Test
  @DisplayName(
      "PERF-8: after receipts, a re-priced lot, sales, a return, an adjustment and a transfer, each"
          + " product's stock row equals its movements and its stock levels, at both branches")
  void stockRowsEqualTheMovements() {
    String supplier = supplier();
    String amoxicillin = product("Amoxicillin 250mg", 1_000, 400, 0);
    String ors = product("ORS sachet", 500, 300, 0);
    receive(supplier, amoxicillin, "A-1", 20, 400, today.plusMonths(6));
    receive(supplier, amoxicillin, "A-2", 10, 500, today.plusMonths(12));
    receive(supplier, ors, "O-1", 15, 300, today.plusMonths(9));
    // More of A-1 at another cost: the batch averages, and its value changes at the branch.
    receive(supplier, amoxicillin, "A-1", 10, 430, today.plusMonths(6));
    assertStockRowsAgree();

    Response sale = sell(amoxicillin, 5);
    sell(ors, 3);
    hq.post(
            "/api/v1/sales/" + sale.string("$.data.uid") + "/returns",
            """
            {"reason":"Brought back","lines":[{"saleLineUid":"%s","quantity":2}]}
            """
                .formatted(sale.string("$.data.lines[0].uid")))
        .expect(201);
    String o1 = batch(ors, "O-1");
    hq.post(
            "/api/v1/stock/adjustments",
            """
            {"productUid":"%s","batchUid":"%s","quantityChange":-2,"reasonCode":"DAMAGE",
             "note":"Sachets torn"}
            """
                .formatted(ors, o1))
        .expect(201);
    assertStockRowsAgree();

    // A transfer of 4 to Arusha: out of HQ when dispatched, into Arusha when received.
    String arusha =
        admin
            .post("/api/v1/branches", "{\"code\":\"AR\",\"name\":\"Arusha\"}")
            .expect(201)
            .string("$.data.uid");
    String managerLogin =
        Staff.login(admin, anonymous, "Juma Manager", "BRANCH_MANAGER", MANAGER_PASSWORD).signIn();
    Api manager = anonymous.as(anonymous.signIn(managerLogin, MANAGER_PASSWORD)).in(tenant, hqUid);
    int before = listed(hq, amoxicillin);
    String transfer =
        manager
            .post(
                "/api/v1/transfers",
                "{\"toBranchUid\":\"%s\",\"lines\":[{\"productUid\":\"%s\",\"quantity\":4}]}"
                    .formatted(arusha, amoxicillin))
            .expect(201)
            .string("$.data.uid");
    assertThat(listed(hq, amoxicillin)).isEqualTo(before - 4);
    assertStockRowsAgree();
    Api atArusha = admin.in(tenant, arusha);
    assertThat(listed(atArusha, amoxicillin)).isZero();
    atArusha.post("/api/v1/transfers/" + transfer + "/receive", null).expect(200);
    assertThat(listed(atArusha, amoxicillin)).isEqualTo(4);
    assertStockRowsAgree();

    // The list and the summary read the rows: on hand, value at cost, nearest expiry, batches.
    Response list = hq.get("/api/v1/stock?limit=100").expect(200);
    for (Map<String, Object> row : stockRows(hqUid)) {
      List<Map<String, Object>> item =
          list.json().read("$.data.items[?(@.productUid == '" + row.get("product_uid") + "')]");
      assertThat(item).hasSize(1);
      assertThat(((Number) item.getFirst().get("quantity")).longValue())
          .isEqualTo(((Number) row.get("levels")).longValue());
      assertThat(new BigDecimal(item.getFirst().get("costValue").toString()))
          .isEqualByComparingTo((BigDecimal) row.get("value"));
      assertThat(item.getFirst().get("nearestExpiry"))
          .isEqualTo(row.get("expiry") == null ? null : row.get("expiry").toString());
      assertThat(((Number) item.getFirst().get("batchCount")).longValue())
          .isEqualTo(((Number) row.get("batches")).longValue());
    }
    BigDecimal atCost =
        decimal(
            "select coalesce(sum(sl.quantity * b.unit_cost), 0) from inventory.stock_levels sl"
                + " join inventory.batches b on b.id = sl.batch_id where sl.branch_uid = ?",
            UUID.fromString(hqUid));
    assertThat(decimalAt(hq.get("/api/v1/stock/summary").expect(200), "$.data.costValue"))
        .isEqualByComparingTo(atCost);
  }

  // ------------------------------------------------------------------ PERF-13 purge

  @Test
  @DisplayName("PERF-13: the nightly retention purges redelivery markers older than 30 days only")
  void purgesOldProcessedEvents() {
    UUID old = UUID.randomUUID();
    UUID fresh = UUID.randomUUID();
    sql(
        "insert into reporting.processed_events (tenant_uid, event_id, processed_at)"
            + " values (?::uuid, ?, now() - interval '40 days'), (?::uuid, ?, now() - interval"
            + " '5 days')",
        tenant,
        old,
        tenant,
        fresh);
    Response run = root.post("/api/v1/platform/retention/run", "{}").expect(200);
    assertThat(run.read("$.data['reporting.processed_events']", Long.class)).isPositive();
    assertThat(count("select count(*) from reporting.processed_events where event_id = ?", old))
        .isZero();
    assertThat(count("select count(*) from reporting.processed_events where event_id = ?", fresh))
        .isOne();
  }

  // ------------------------------------------------------------------ PERF-17 statements

  @Test
  @DisplayName(
      "PERF-17: a statement pages the last 90 days by default, with the balance brought forward"
          + " from the daily balances and running balances that continue across pages")
  void statementPages() {
    String customer = customer();
    int[] amounts = {500, 300, 200, 100, 50, 25};
    long[] daysAgo = {120, 100, 30, 20, 10, 0};
    for (int i = 0; i < amounts.length; i++) {
      admin
          .post(
              "/api/v1/receivables/payments",
              """
              {"customerUid":"%s","amount":%d,"method":"CASH","reference":"S-%d","date":"%s"}
              """
                  .formatted(customer, amounts[i], i, today.minusDays(daysAgo[i])))
          .expect(201);
    }
    String path = "/api/v1/receivables/accounts/" + customer + "/statement";

    Response first = admin.get(path + "?limit=2").expect(200);
    assertThat(first.string("$.data.from")).isEqualTo(today.minusDays(89).toString());
    assertThat(first.string("$.data.to")).isEqualTo(today.toString());
    assertThat(money(first, "$.data.openingBalance")).isEqualTo(-800.0);
    assertThat(runningBalances(first)).containsExactly(-1_000.0, -1_100.0);
    String cursor = first.string("$.data.entries.nextCursor");
    assertThat(cursor).isNotNull();

    Response second = admin.get(path + "?limit=2&cursor=" + cursor).expect(200);
    assertThat(runningBalances(second)).containsExactly(-1_150.0, -1_175.0);
    assertThat(second.json().read("$.data.entries.nextCursor", String.class)).isNull();

    // A chosen period: everything from 130 days ago, nothing brought forward; numbered pages.
    Response all =
        admin.get(path + "?from=" + today.minusDays(130) + "&to=" + today + "&limit=4").expect(200);
    assertThat(money(all, "$.data.openingBalance")).isZero();
    assertThat(runningBalances(all)).containsExactly(-500.0, -800.0, -1_000.0, -1_100.0);
    Response rest =
        admin
            .get(
                path
                    + "?from="
                    + today.minusDays(130)
                    + "&to="
                    + today
                    + "&limit=4&cursor="
                    + all.string("$.data.entries.nextCursor"))
            .expect(200);
    assertThat(runningBalances(rest)).containsExactly(-1_150.0, -1_175.0);
    // The opening balance from the read model equals the entries before the period.
    assertThat(
            decimal(
                "select coalesce(sum(amount), 0) from receivables.entries where customer_uid = ?"
                    + " and entry_date < ?",
                UUID.fromString(customer),
                today.minusDays(89)))
        .isEqualByComparingTo("-800");
    assertThat(
            admin
                .get(path + "?from=" + today + "&to=" + today.minusDays(1))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_period");
  }

  // ------------------------------------------------------------------ PERF-18 periods

  @Test
  @DisplayName("PERF-18: a report covers at most 366 days (422 period_too_long); a year is fine")
  void reportPeriodsAreLimited() {
    String year = "?from=" + today.minusDays(365) + "&to=" + today;
    String tooLong = "?from=" + today.minusDays(366) + "&to=" + today;
    for (String report :
        List.of("sales", "products", "profit-loss", "payments", "vat", "cashiers")) {
      hq.get("/api/v1/reports/" + report + year).expect(200);
      assertThat(hq.get("/api/v1/reports/" + report + tooLong).expect(422).string("$.reason"))
          .as(report)
          .isEqualTo("period_too_long");
    }
    assertThat(
            hq.get("/api/v1/reports/sales?from=" + today + "&to=" + today.minusDays(1))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("invalid_range");
  }

  // ------------------------------------------------------------------ helpers

  /** Every product row of the pharmacy against its movements and stock levels, at each branch. */
  private void assertStockRowsAgree() {
    assertThat(
            count(
                "select count(*) from (select distinct branch_uid, product_uid"
                    + " from inventory.movements) m"
                    + " where not exists (select 1 from inventory.product_stock s"
                    + " where s.branch_uid = m.branch_uid and s.product_uid = m.product_uid)"))
        .as("every branch and product with movements has a stock row")
        .isZero();
    List<Map<String, Object>> rows = stockRows(null);
    assertThat(rows).isNotEmpty();
    for (Map<String, Object> row : rows) {
      String what = row.get("branch_uid") + "/" + row.get("product_uid");
      assertThat(((Number) row.get("quantity")).longValue())
          .as("on hand of %s against its movements", what)
          .isEqualTo(((Number) row.get("moved")).longValue());
      assertThat(((Number) row.get("quantity")).longValue())
          .as("on hand of %s against its stock levels", what)
          .isEqualTo(((Number) row.get("levels")).longValue());
      assertThat((BigDecimal) row.get("cost_value"))
          .as("value at cost of %s", what)
          .isEqualByComparingTo((BigDecimal) row.get("value"));
      assertThat(row.get("nearest_expiry"))
          .as("nearest expiry of %s", what)
          .isEqualTo(row.get("expiry"));
      assertThat(((Number) row.get("batch_count")).longValue())
          .as("batches of %s", what)
          .isEqualTo(((Number) row.get("batches")).longValue());
    }
  }

  private List<Map<String, Object>> stockRows(String branch) {
    return rows(
        "select s.branch_uid, s.product_uid, s.quantity, s.cost_value, s.nearest_expiry,"
            + " s.batch_count,"
            + " (select coalesce(sum(m.quantity_change), 0) from inventory.movements m"
            + "   where m.branch_uid = s.branch_uid and m.product_uid = s.product_uid) as moved,"
            + " (select coalesce(sum(sl.quantity), 0) from inventory.stock_levels sl"
            + "   where sl.branch_uid = s.branch_uid and sl.product_uid = s.product_uid) as levels,"
            + " (select coalesce(sum(sl.quantity * b.unit_cost), 0) from inventory.stock_levels sl"
            + "   join inventory.batches b on b.id = sl.batch_id"
            + "   where sl.branch_uid = s.branch_uid and sl.product_uid = s.product_uid) as value,"
            + " (select min(b.expiry_date) filter (where sl.quantity > 0)"
            + "   from inventory.stock_levels sl join inventory.batches b on b.id = sl.batch_id"
            + "   where sl.branch_uid = s.branch_uid and sl.product_uid = s.product_uid) as expiry,"
            + " (select count(*) filter (where sl.quantity > 0) from inventory.stock_levels sl"
            + "   where sl.branch_uid = s.branch_uid and sl.product_uid = s.product_uid) as batches"
            + " from inventory.product_stock s"
            + " where (cast(? as uuid) is null or s.branch_uid = cast(? as uuid))",
        branch == null ? null : UUID.fromString(branch),
        branch == null ? null : UUID.fromString(branch));
  }

  /** The product's units on the stock list of the request's branch (0 when it holds none). */
  private static int listed(Api at, String product) {
    List<Object> quantities =
        at.get("/api/v1/stock?limit=100")
            .expect(200)
            .json()
            .read("$.data.items[?(@.productUid == '" + product + "')].quantity");
    return quantities.isEmpty() ? 0 : ((Number) quantities.getFirst()).intValue();
  }

  /** Debits and credits per account from the journal's lines, up to {@code asOf} (null: all). */
  private Map<String, BigDecimal[]> journalSums(LocalDate asOf) {
    Map<String, BigDecimal[]> sums = new HashMap<>();
    for (Map<String, Object> row :
        rows(
            "select l.account_code, sum(l.debit) as debit, sum(l.credit) as credit"
                + " from finance.journal_lines l join finance.journal_entries e on e.id = l.entry_id"
                + " where cast(? as date) is null or e.entry_date <= cast(? as date)"
                + " group by l.account_code",
            asOf,
            asOf)) {
      sums.put(
          (String) row.get("account_code"),
          new BigDecimal[] {(BigDecimal) row.get("debit"), (BigDecimal) row.get("credit")});
    }
    return sums;
  }

  private static BigDecimal[] zeros() {
    return new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO};
  }

  private List<Map<String, Object>> balanceMismatches(int days) {
    return admin
        .get("/api/v1/finance/reconciliation/balances?days=" + days)
        .expect(200)
        .json()
        .read("$.data");
  }

  private static List<Double> runningBalances(Response statement) {
    List<Object> values = statement.json().read("$.data.entries.items[*].runningBalance");
    return values.stream().map(v -> Double.parseDouble(v.toString())).toList();
  }

  private static BigDecimal decimalAt(Response response, String path) {
    return new BigDecimal(response.json().read(path, Object.class).toString());
  }

  private static double money(Response response, String path) {
    return Double.parseDouble(response.json().read(path, Object.class).toString());
  }

  private String supplier() {
    return admin
        .post("/api/v1/suppliers", "{\"name\":\"Lake Drugs\",\"paymentTerms\":\"NET_30\"}")
        .expect(201)
        .string("$.data.uid");
  }

  private String customer() {
    return admin
        .post(
            "/api/v1/customers",
            "{\"name\":\"Clinic %s\",\"type\":\"WHOLESALE\",\"creditLimit\":100000}"
                .formatted(UUID.randomUUID().toString().substring(0, 6)))
        .expect(201)
        .string("$.data.uid");
  }

  private String product(String name, int price, int cost, int taxPercent) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
             "sellingPrice":%d,"costPrice":%d,"taxRatePercent":%d}
            """
                .formatted(name, name, price, cost, taxPercent))
        .expect(201)
        .string("$.data.uid");
  }

  private void receive(
      String supplier, String product, String batch, int quantity, int cost, LocalDate expiry) {
    hq.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"INV-%s","lines":[
              {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%d}]}
            """
                .formatted(
                    supplier,
                    UUID.randomUUID().toString().substring(0, 8),
                    product,
                    batch,
                    expiry,
                    quantity,
                    cost))
        .expect(201);
  }

  /** A cash sale, paid generously (the change is given). */
  private Response sell(String product, int quantity) {
    return hq.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":%d}],
             "payments":[{"method":"CASH","amount":%d}]}
            """
                .formatted(UUID.randomUUID(), product, quantity, quantity * 3_000))
        .expect(201);
  }

  private String batch(String product, String number) {
    List<String> uids =
        hq.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[?(@.batchNumber == '" + number + "')].uid");
    return uids.getFirst();
  }

  private long count(String query, Object... args) {
    return ((Number) rows(query, args).getFirst().values().iterator().next()).longValue();
  }

  private BigDecimal decimal(String query, Object... args) {
    return (BigDecimal) rows(query, args).getFirst().values().iterator().next();
  }

  private List<String> strings(String query, Object... args) {
    return rows(query, args).stream().map(row -> (String) row.values().iterator().next()).toList();
  }

  /** A statement as the schema owner, in this pharmacy (row-level security is forced on it too). */
  private void sql(String statement, Object... args) {
    run(statement, args, false);
  }

  private List<Map<String, Object>> rows(String query, Object... args) {
    return run(query, args, true);
  }

  private List<Map<String, Object>> run(String statement, Object[] args, boolean query) {
    try (Connection c = DriverManager.getConnection(url, owner, ownerPassword)) {
      c.setAutoCommit(false);
      try (PreparedStatement scope =
          c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        scope.setString(1, tenant);
        scope.execute();
      }
      List<Map<String, Object>> rows = new ArrayList<>();
      try (PreparedStatement s = c.prepareStatement(statement)) {
        for (int i = 0; i < args.length; i++) {
          s.setObject(i + 1, args[i] instanceof LocalDate d ? java.sql.Date.valueOf(d) : args[i]);
        }
        if (query) {
          try (ResultSet r = s.executeQuery()) {
            ResultSetMetaData meta = r.getMetaData();
            while (r.next()) {
              Map<String, Object> row = new LinkedHashMap<>();
              for (int i = 1; i <= meta.getColumnCount(); i++) {
                Object value = r.getObject(i);
                row.put(
                    meta.getColumnLabel(i),
                    value instanceof java.sql.Date d ? d.toLocalDate() : value);
              }
              rows.add(row);
            }
          }
        } else {
          s.execute();
        }
      }
      c.commit();
      return rows;
    } catch (java.sql.SQLException failure) {
      throw new IllegalStateException(failure);
    }
  }
}
