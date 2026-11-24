package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.pharmacy.support.Api.eventually;

import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.FilterChain;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.delivery.edge.EdgeAccessCache;
import tz.co.pharmacy.delivery.edge.InFlightLimit;
import tz.co.pharmacy.platform.config.AsyncExecution;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.signals.PostgresSignals;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Staff;
import tz.co.pharmacy.support.StatementCounting;
import tz.co.pharmacy.support.StatementCounting.Tally;

/**
 * Load limits and waste (adversarial review PERF-2, PERF-5, PERF-9, PERF-16): the cap on requests
 * in progress, the bounded pool of the async reactions, the time limits on every database
 * connection, exports read by keyset, capped list totals, the edge's access cache (and its
 * revocation on every instance) and lists without N+1 queries. Round trips are counted per request
 * ({@link StatementCounting}): every statement and every commit on the application's connections.
 */
@Import(StatementCounting.Configuration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LoadLimitsTest extends IntegrationTest {

  @Autowired DataSource dataSource;
  @Autowired PlatformTransactionManager transactions;
  @Autowired SingleRunner runner;
  @Autowired EdgeAccessCache accessCache;
  @Autowired InFlightLimit inFlight;

  @Autowired
  @Qualifier(AsyncExecution.EXECUTOR)
  ThreadPoolTaskExecutor reactions;

  private Api anonymous;
  private Api root;
  private Api admin;
  private Api till;
  private String tenant;
  private String branch;
  private String code;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    code = "LL" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT);
    tenant = register(code);
    admin = anonymous.as(anonymous.signIn(email(code), "Admin#2026x")).in(tenant, null);
    branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenant, branch);
  }

  private String register(String code) {
    return root.post(
            "/api/v1/platform/tenants",
            """
            {"code":"%s","name":"Load %s","legalName":"Load %s Ltd","country":"TZ",
             "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
             "headOffice":{"code":"HQ","name":"Head Office"},
             "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
            """
                .formatted(code, code, code, email(code)))
        .expect(201)
        .string("$.data.uid");
  }

  private static String email(String code) {
    return code.toLowerCase(Locale.ROOT) + "@test.local";
  }

  /** One request, counted. */
  private static Tally measure(Api api, String path) {
    String id = StatementCounting.newId();
    api.get(path, "X-Correlation-Id", id).expect(200);
    Tally tally = StatementCounting.of(id);
    System.out.println("LoadLimitsTest " + path + ": " + tally);
    return tally;
  }

  private static long touching(Tally tally, String table) {
    return tally.sql().stream().filter(sql -> sql.toLowerCase(Locale.ROOT).contains(table)).count();
  }

  // ----------------------------------------------------------------------------- PERF-2

  @Test
  @DisplayName("past the cap on requests in progress a request is refused 503 with Retry-After")
  void globalCap() throws Exception {
    assertThat(inFlight.limit()).isEqualTo(100);
    InFlightLimit cap =
        new InFlightLimit(
            2,
            0,
            2,
            new StaticListableBeanFactory()
                .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    CountDownLatch inside = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    FilterChain holding =
        (request, response) -> {
          inside.countDown();
          try {
            release.await(10, TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
        };
    ExecutorService threads = Executors.newFixedThreadPool(2);
    try {
      List<Future<?>> held =
          List.of(
              threads.submit(() -> filter(cap, "/api/v1/products", holding)),
              threads.submit(() -> filter(cap, "/api/v1/sales", holding)));
      assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(cap.inFlight()).isEqualTo(2);

      MockHttpServletResponse refused = filter(cap, "/api/v1/stock", (q, r) -> {});
      assertThat(refused.getStatus()).isEqualTo(503);
      assertThat(refused.getHeader("Retry-After")).isEqualTo("2");
      assertThat(refused.getContentAsString()).contains("\"reason\":\"server_busy\"");
      assertThat(refused.getHeader("X-Correlation-Id")).isNotBlank();
      // The live stream and the health probes are not counted.
      assertThat(filter(cap, "/api/v1/notifications/stream", (q, r) -> {}).getStatus())
          .isEqualTo(200);
      assertThat(filter(cap, "/readyz", (q, r) -> {}).getStatus()).isEqualTo(200);

      release.countDown();
      for (Future<?> f : held) {
        f.get(10, TimeUnit.SECONDS);
      }
      assertThat(cap.inFlight()).isZero();
      assertThat(filter(cap, "/api/v1/stock", (q, r) -> {}).getStatus()).isEqualTo(200);
    } finally {
      release.countDown();
      threads.shutdownNow();
    }
  }

  private static MockHttpServletResponse filter(InFlightLimit cap, String path, FilterChain chain) {
    MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
    MockHttpServletResponse response = new MockHttpServletResponse();
    try {
      cap.doFilter(request, response, chain);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    return response;
  }

  @Test
  @DisplayName("the async reactions run on one bounded pool with a bounded queue")
  void boundedReactions() {
    assertThat(reactions.getCorePoolSize()).isEqualTo(8);
    assertThat(reactions.getMaxPoolSize()).isEqualTo(8);
    assertThat(reactions.getQueueCapacity()).isEqualTo(10_000);
    long before = reactions.getThreadPoolExecutor().getCompletedTaskCount();
    // A new pharmacy's reactions (its notices, the demo currencies) run after commit, here.
    register("LR" + UUID.randomUUID().toString().substring(0, 4).toUpperCase(Locale.ROOT));
    eventually(() -> reactions.getThreadPoolExecutor().getCompletedTaskCount() > before);
  }

  @Test
  @DisplayName("every pooled connection has the statement, lock and idle-in-transaction limits")
  void connectionTimeouts() {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertThat(jdbc.queryForObject("show statement_timeout", String.class)).isEqualTo("30s");
    assertThat(jdbc.queryForObject("show lock_timeout", String.class)).isEqualTo("5s");
    assertThat(jdbc.queryForObject("show idle_in_transaction_session_timeout", String.class))
        .isEqualTo("1min");
    assertThat(jdbc.queryForObject("show idle_session_timeout", String.class)).isEqualTo("0");

    // A transaction that declares a timeout, and a job's transaction, raise it for themselves.
    TransactionTemplate longer = new TransactionTemplate(transactions);
    longer.setTimeout(120);
    String declared =
        longer.execute(s -> jdbc.queryForObject("show statement_timeout", String.class));
    assertThat(declared).isEqualTo("2min");
    String[] inJob = new String[1];
    runner.runHere(
        "load-limits-check",
        run ->
            inJob[0] =
                new TransactionTemplate(transactions)
                    .execute(s -> jdbc.queryForObject("show statement_timeout", String.class)));
    assertThat(inJob[0]).isEqualTo("5min");
    // SET LOCAL: the pooled connections are back at the limit afterwards.
    for (int i = 0; i < 5; i++) {
      String after =
          new TransactionTemplate(transactions)
              .execute(s -> jdbc.queryForObject("show statement_timeout", String.class));
      assertThat(after).isEqualTo("30s");
    }
  }

  @Test
  @DisplayName("the job lock's session and the LISTEN connection outlive the idle limits")
  void lockAndListenerSurvive() throws Exception {
    HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
    try (Connection connection =
            DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute("set idle_in_transaction_session_timeout = '1s'");
      // As SingleRunner holds a job's lock: a session lock taken in auto-commit, then idle.
      statement.execute("select pg_try_advisory_lock(hashtextextended('job:load-limits', 0))");
      Thread.sleep(1_600);
      try (ResultSet held =
          statement.executeQuery(
              "select count(*) from pg_locks where locktype = 'advisory'"
                  + " and pid = pg_backend_pid()")) {
        assertThat(held.next()).isTrue();
        assertThat(held.getInt(1)).isEqualTo(1);
      }
      // What the limit is for: a session idle inside an open transaction is ended.
      connection.setAutoCommit(false);
      statement.execute("select 1");
      Thread.sleep(1_600);
      assertThatThrownBy(() -> statement.execute("select 1")).isInstanceOf(SQLException.class);
    }

    // The signal listener's connection is outside the pool and idle outside a transaction.
    PostgresSignals signals = new PostgresSignals(dataSource, Duration.ofSeconds(30));
    signals.subscribe("load_limits_probe", payload -> {});
    signals.start();
    try {
      eventually(signals::connected);
      Thread.sleep(1_000);
      List<String> states =
          new JdbcTemplate(dataSource)
              .queryForList(
                  "select state from pg_stat_activity where application_name = 'pharmacy-signals'"
                      + " and usename = current_user",
                  String.class);
      assertThat(states).isNotEmpty().allMatch("idle"::equals);
      assertThat(signals.connected()).isTrue();
    } finally {
      signals.stop();
    }
  }

  // ----------------------------------------------------------------------------- PERF-5

  /** {@code count} customers of the pharmacy, written straight in the database. */
  private void customers(int count) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    TenantContext.callAs(
        UUID.fromString(tenant),
        () ->
            new TransactionTemplate(transactions)
                .execute(
                    s ->
                        jdbc.update(
                            "insert into partners.customers (uid, tenant_uid, code, name, type,"
                                + " created_at, created_by) select gen_random_uuid(), ?, 'C' || n,"
                                + " 'Customer ' || n, 'RETAIL', now(), 'load-test'"
                                + " from generate_series(1, ?) n",
                            UUID.fromString(tenant),
                            count)));
  }

  @Test
  @DisplayName("an export reads page by page by keyset: no count, no offset")
  void exportByKeyset() {
    customers(1_100);
    String id = StatementCounting.newId();
    Response csv =
        admin.get("/api/v1/customers/export?format=csv", "X-Correlation-Id", id).expect(200);
    Tally tally = StatementCounting.of(id);
    System.out.println("LoadLimitsTest customers export: " + tally);
    assertThat(csv.body().split("\r\n")).hasSize(1 + 1_100);
    List<String> reads =
        tally.sql().stream()
            .map(sql -> sql.toLowerCase(Locale.ROOT))
            .filter(sql -> sql.contains("partners.customers"))
            .toList();
    // 1,100 rows in pages of 500: three reads, the later two after the last uid of the one before.
    assertThat(reads).hasSize(3);
    assertThat(reads).noneMatch(sql -> sql.contains("count("));
    assertThat(reads).noneMatch(sql -> sql.contains("offset"));
    assertThat(reads.subList(1, 3)).allMatch(sql -> sql.contains("uid<"));
  }

  @Test
  @DisplayName("a numbered list counts at most 10,001 rows and says 10,000+")
  void cappedTotals() {
    customers(10_050);
    String id = StatementCounting.newId();
    Response page =
        admin.get("/api/v1/customers?page=2&limit=25", "X-Correlation-Id", id).expect(200);
    assertThat(page.read("$.data.total", Integer.class)).isEqualTo(10_000);
    assertThat(page.read("$.data.totalCapped", Boolean.class)).isTrue();
    assertThat(page.read("$.data.items.length()", Integer.class)).isEqualTo(25);
    List<String> counts =
        StatementCounting.of(id).sql().stream()
            .map(sql -> sql.toLowerCase(Locale.ROOT))
            .filter(sql -> sql.contains("partners.customers") && sql.contains("count("))
            .toList();
    assertThat(counts).hasSize(1);
    assertThat(counts.getFirst()).containsAnyOf("fetch first", "limit");

    // Under the cap the total is exact; a short list is not counted at all.
    Response few = admin.get("/api/v1/suppliers?page=1&limit=5").expect(200);
    assertThat(few.read("$.data.total", Integer.class)).isZero();
    assertThat(few.read("$.data.totalCapped", Boolean.class)).isFalse();
    // Without a page number there is no total.
    Response cursor = admin.get("/api/v1/customers?limit=5").expect(200);
    assertThat(cursor.read("$.data.total", Object.class)).isNull();
    assertThat(cursor.read("$.data.totalCapped", Object.class)).isNull();
  }

  // ----------------------------------------------------------------------------- PERF-9

  @Test
  @DisplayName("the edge reads the pharmacy, membership and branch once per 30 s: 1 transaction")
  void edgeCache() {
    Api fresh = anonymous.as(anonymous.signIn(email(code), "Admin#2026x"));
    // The session's first request in the pharmacy notes the visit (a write, every 5 minutes).
    fresh.in(tenant, null).get("/api/v1/branches").expect(200);
    Api inBranch = fresh.in(tenant, branch);
    Tally miss = measure(inBranch, "/api/v1/shifts/current");
    Tally hit = measure(inBranch, "/api/v1/shifts/current");
    Tally again = measure(inBranch, "/api/v1/shifts/current");
    // Before (Batch 6): 4 transactions and 16 round trips before every handler.
    assertThat(miss.edgeTransactions()).isEqualTo(2);
    assertThat(hit.edgeTransactions()).isEqualTo(1);
    assertThat(again.edgeTransactions()).isEqualTo(1);
    assertThat(hit.edgeRoundTrips()).isLessThanOrEqualTo(4).isLessThan(miss.edgeRoundTrips());
    assertThat(miss.edgeRoundTrips()).isLessThan(16);
  }

  @Test
  @DisplayName("signing out, a deactivated member and a suspended pharmacy take effect at once")
  void revocation() {
    String password = "Cashier#2026x";
    Staff.Login cashier = Staff.login(admin, anonymous, "Cashier", "CASHIER", password);
    Api cashierApi = anonymous.as(anonymous.signIn(cashier.signIn(), password)).in(tenant, branch);
    String cashierUid = cashierApi.get("/api/v1/auth/me").expect(200).string("$.data.user.uid");
    UUID cashierUser = UUID.fromString(cashierUid);
    settle();

    // Signing out: the session is refused at once, and its cached access is gone.
    cashierApi.get("/api/v1/shifts/current").expect(200);
    assertThat(accessCache.holdsUser(cashierUser)).isTrue();
    cashierApi.post("/api/v1/auth/sign-out", null).expect(200);
    assertThat(accessCache.holdsUser(cashierUser)).isFalse();
    assertThat(cashierApi.get("/api/v1/shifts/current").status()).isEqualTo(401);
    settle();

    // Deactivated: refused at the next request, well inside the 30 seconds.
    Api again = anonymous.as(anonymous.signIn(cashier.signIn(), password)).in(tenant, branch);
    again.get("/api/v1/shifts/current").expect(200);
    assertThat(accessCache.holdsUser(cashierUser)).isTrue();
    List<String> roles =
        admin.get("/api/v1/roles").expect(200).json().read("$.data[?(@.code == 'CASHIER')].uid");
    admin
        .put(
            "/api/v1/users/" + cashier.membershipUid(),
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"active\":false}"
                .formatted(roles.getFirst()))
        .expect(200);
    assertThat(accessCache.holdsUser(cashierUser)).isFalse();
    assertThat(again.get("/api/v1/shifts/current").status()).isIn(401, 403);
    settle();

    // Suspended: the pharmacy's staff are refused at their next request.
    till.get("/api/v1/shifts/current").expect(200);
    assertThat(accessCache.holdsTenant(UUID.fromString(tenant))).isTrue();
    root.post(
            "/api/v1/platform/tenants/" + tenant + "/suspend",
            "{\"kind\":\"OTHER\",\"reason\":\"Load limits test\"}")
        .expect(200);
    assertThat(accessCache.holdsTenant(UUID.fromString(tenant))).isFalse();
    Response refused = till.get("/api/v1/shifts/current");
    assertThat(refused.status()).isEqualTo(403);
    assertThat(refused.string("$.reason")).isEqualTo("tenant_suspended");
  }

  /**
   * Lets the signals of the evictions just made come back to this instance (its listener reads
   * every half second), so they do not evict what the test caches next.
   */
  private static void settle() {
    try {
      Thread.sleep(1_500);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  @DisplayName("an eviction reaches the other instances through PostgreSQL NOTIFY")
  void evictionAcrossInstances() {
    PostgresSignals signalsB = new PostgresSignals(dataSource, Duration.ofSeconds(30));
    EdgeAccessCache cacheB = new EdgeAccessCache(signalsB, 30, 100);
    signalsB.start();
    try {
      eventually(signalsB::connected);
      UUID tenantUid = UUID.fromString(tenant);
      UUID user = UUID.randomUUID();
      cacheB.get(
          new EdgeAccessCache.Key(UUID.randomUUID(), user, false, tenantUid, null),
          () -> new EdgeAccessCache.Access(null, null, null),
          access -> true);
      assertThat(cacheB.holdsTenant(tenantUid)).isTrue();
      eventually(
          () -> {
            // Instance A (the application) suspends, say; B hears it.
            accessCache.forgetTenant(tenantUid);
            return !cacheB.holdsTenant(tenantUid);
          });
    } finally {
      signalsB.stop();
    }
  }

  // ----------------------------------------------------------------------------- PERF-16

  @Test
  @DisplayName("the purchase order, goods receipt and shift lists read their rows in one go")
  void noNPlusOne() {
    String supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Load Supplier\",\"paymentTerms\":\"COD\"}")
            .expect(201)
            .string("$.data.uid");
    String[] products = new String[2];
    for (int i = 0; i < 2; i++) {
      products[i] =
          admin
              .post(
                  "/api/v1/products",
                  """
                  {"name":"Load %d","genericName":"Generic","dosageForm":"TABLET",
                   "schedule":"OTC","sellingPrice":1000,"costPrice":500,"taxRatePercent":0,
                   "storage":"ROOM_TEMPERATURE"}
                  """
                      .formatted(i))
              .expect(201)
              .string("$.data.uid");
    }
    for (int i = 0; i < 12; i++) {
      till.post(
              "/api/v1/purchase-orders",
              """
              {"supplierUid":"%s","lines":[{"productUid":"%s","quantity":10,"unitCost":2},
               {"productUid":"%s","quantity":5,"unitCost":3}]}
              """
                  .formatted(supplier, products[0], products[1]))
          .expect(201);
    }
    for (int i = 0; i < 6; i++) {
      till.post(
              "/api/v1/goods-receipts",
              """
              {"supplierUid":"%s","supplierInvoiceNumber":"SI-%d","lines":[
                {"productUid":"%s","batchNumber":"A-%d","expiryDate":"%s","quantity":10,"unitCost":800},
                {"productUid":"%s","batchNumber":"B-%d","expiryDate":"%s","quantity":4,"unitCost":750}]}
              """
                  .formatted(
                      supplier,
                      i,
                      products[0],
                      i,
                      LocalDate.now().plusYears(1),
                      products[1],
                      i,
                      LocalDate.now().plusYears(1)))
          .expect(201);
    }
    for (int i = 0; i < 6; i++) {
      till.post("/api/v1/shifts/open", "{\"openingFloat\":0}").expect(201);
      till.post("/api/v1/shifts/close", "{\"countedCash\":0}").expect(200);
    }
    till.get("/api/v1/shifts/current").expect(200); // the edge's cache is warm

    Tally orders = measure(till, "/api/v1/purchase-orders?limit=25");
    Tally receipts = measure(till, "/api/v1/goods-receipts?limit=25");
    Tally shifts = measure(till, "/api/v1/shifts?limit=25");
    // Before: one statement per order's lines (28 round trips for 25 orders), per receipt's lines
    // (13 for 10) and four per shift (43 for 10).
    assertThat(touching(orders, "purchase_order_lines")).isEqualTo(1);
    assertThat(touching(receipts, "goods_receipt_lines")).isEqualTo(1);
    assertThat(orders.handlerRoundTrips()).isLessThanOrEqualTo(4);
    assertThat(receipts.handlerRoundTrips()).isLessThanOrEqualTo(4);
    assertThat(touching(shifts, "sales.sales")).isLessThanOrEqualTo(3);
    // 7, plus the one read of the pharmacy's base currency that money rounding makes per
    // transaction (STD-17, TenantMoneyScale): constant, not one per shift.
    assertThat(shifts.handlerRoundTrips()).isLessThanOrEqualTo(8);

    Response list = till.get("/api/v1/shifts?limit=25").expect(200);
    assertThat(list.read("$.data.items.length()", Integer.class)).isEqualTo(6);
    assertThat(list.read("$.data.items[0].sales", Integer.class)).isZero();
    Response orderList = till.get("/api/v1/purchase-orders?limit=25").expect(200);
    assertThat(orderList.read("$.data.items.length()", Integer.class)).isEqualTo(12);
    assertThat(orderList.read("$.data.items[0].lines.length()", Integer.class)).isEqualTo(2);
  }
}
