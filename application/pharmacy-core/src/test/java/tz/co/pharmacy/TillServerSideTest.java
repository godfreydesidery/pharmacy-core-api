package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.FilterChain;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tz.co.pharmacy.delivery.edge.InFlightLimit;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * The server half of Batch 8, what the till relies on: a sale's idempotency key (WEB-2), push
 * payments bound to their sale (WEB-9), problem members on every path, a pharmacy's suspension kind
 * (UX-8), value members the app can put into its own words, POS products by uid, and the shape of
 * {@code server_busy}.
 */
class TillServerSideTest extends IntegrationTest {

  @Autowired DataSource dataSource;

  private Api anonymous;
  private Api root;
  private String tenant;
  private String email;
  private String token;
  private Api admin;
  private Api till;
  private String product;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "TS" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Till %s","legalName":"Till %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    token = anonymous.signIn(email, "Admin#2026x-strong");
    admin = anonymous.as(token).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenant, branch);
    product = product("ORS Sachet", 50);
  }

  // ------------------------------------------------------------------ 1. sale idempotency (WEB-2)

  @Test
  @DisplayName(
      "the same key and the same cart answer with the one sale, however the JSON is spaced")
  void sameKeySameCart() {
    String key = UUID.randomUUID().toString();
    Response first = sell(key, cart(key, product, 2, "1600")).expect(201);
    String uid = first.string("$.data.uid");

    // The till sends it again, byte for byte, with the key in the header too.
    assertThat(sell(key, cart(key, product, 2, "1600")).expect(201).string("$.data.uid"))
        .isEqualTo(uid);
    // The same request written differently is still the same request.
    String respaced =
        """
        {  "payments" : [ {"amount":1600.00, "method":"CASH"} ],
           "lines":[{"quantity":2,"productUid":"%s"}], "idempotencyKey":"%s" }
        """
            .formatted(product, key);
    assertThat(sell(key, respaced).expect(201).string("$.data.uid")).isEqualTo(uid);
    assertThat(onHand(product)).isEqualTo(48);

    // GET /sales?idempotencyKey= finds it.
    Response found = till.get("/api/v1/sales?idempotencyKey=" + key).expect(200);
    assertThat(found.json().<List<String>>read("$.data.items[*].uid")).containsExactly(uid);
    assertThat(
            till.get("/api/v1/sales?idempotencyKey=" + UUID.randomUUID())
                .expect(200)
                .json()
                .<List<Object>>read("$.data.items"))
        .isEmpty();
  }

  @Test
  @DisplayName("the same key after a sale with another cart is 422 idempotency_key_reused")
  void sameKeyOtherCart() {
    String key = UUID.randomUUID().toString();
    String uid = sell(key, cart(key, product, 2, "1600")).expect(201).string("$.data.uid");
    Response reused = sell(key, cart(key, product, 3, "2400")).expect(422);
    assertThat(reused.string("$.reason")).isEqualTo("idempotency_key_reused");
    assertThat(reused.string("$.saleUid")).isEqualTo(uid);
    assertThat(reused.string("$.saleNumber")).startsWith("INV-");
    // Another payment for the same cart is another request too.
    assertThat(
            sell(
                    key,
                    """
                    {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":2}],
                     "payments":[{"method":"CASH","amount":2000}]}
                    """
                        .formatted(key, product))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("idempotency_key_reused");
    assertThat(onHand(product)).isEqualTo(48);
  }

  @Test
  @DisplayName("a refused first attempt records nothing: the key stays free for the changed cart")
  void refusedAttemptLeavesTheKeyFree() {
    String key = UUID.randomUUID().toString();
    Response short_ = sell(key, cart(key, product, 80, "64000")).expect(422);
    assertThat(short_.string("$.reason")).isEqualTo("insufficient_stock");
    assertThat(
            till.get("/api/v1/sales?idempotencyKey=" + key)
                .expect(200)
                .json()
                .<List<Object>>read("$.data.items"))
        .isEmpty();
    // A short payment is refused after the stock was taken and the number drawn: rolled back too.
    assertThat(sell(key, cart(key, product, 2, "1000")).expect(422).string("$.reason"))
        .isEqualTo("payment_short");
    assertThat(onHand(product)).isEqualTo(50);

    // The cashier changes the cart and sends it with the same key: it is a new sale.
    String uid = sell(key, cart(key, product, 2, "1600")).expect(201).string("$.data.uid");
    assertThat(
            till.get("/api/v1/sales?idempotencyKey=" + key)
                .expect(200)
                .json()
                .<List<String>>read("$.data.items[*].uid"))
        .containsExactly(uid);
    assertThat(onHand(product)).isEqualTo(48);
  }

  @Test
  @DisplayName("concurrent requests with one key wait for each other: one sale, others refused")
  void concurrentDuplicates() {
    String key = UUID.randomUUID().toString();
    String a = cart(key, product, 1, "800");
    String b = cart(key, product, 2, "1600");
    List<Response> answers =
        IntStream.range(0, 12)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> sell(key, i % 2 == 0 ? a : b)))
            .toList()
            .stream()
            .map(CompletableFuture::join)
            .toList();

    // Each waited on the key's lock: none was answered 409, and only one sale was made.
    assertThat(answers).allSatisfy(r -> assertThat(r.status()).as(r.body()).isIn(201, 422));
    List<Response> made = answers.stream().filter(r -> r.status() == 201).toList();
    assertThat(made.stream().map(r -> r.string("$.data.uid")).collect(Collectors.toSet()))
        .hasSize(1);
    assertThat(answers.stream().filter(r -> r.status() == 422))
        .allSatisfy(r -> assertThat(r.string("$.reason")).isEqualTo("idempotency_key_reused"))
        .hasSize(6);
    int sold = made.getFirst().read("$.data.lines[0].quantity", Integer.class);
    assertThat(onHand(product)).isEqualTo(50 - sold);
  }

  // ----------------------------------------------------------------- 2. push payments (WEB-9)

  @Test
  @DisplayName("a push request bound to a sale's key pays that sale only, once")
  void pushBoundToItsSale() {
    simulator();
    String key = UUID.randomUUID().toString();
    Response pushed =
        till.post(
                "/api/v1/pos/payment-requests",
                """
                {"methodCode":"SIMULATOR","amount":800,"currency":"TZS","phone":"+255754000111",
                 "saleKey":"%s"}
                """
                    .formatted(key))
            .expect(201);
    String intent = pushed.string("$.data.uid");
    assertThat(pushed.string("$.data.saleKey")).isEqualTo(key);
    assertThat(
            till.get("/api/v1/pos/payment-requests/" + intent).expect(200).string("$.data.status"))
        .isEqualTo("CONFIRMED");

    // Another sale (another key, or none) cannot be paid with it.
    String other = UUID.randomUUID().toString();
    Response mismatch = sell(other, pushCart(other, intent)).expect(422);
    assertThat(mismatch.string("$.reason")).isEqualTo("payment_mismatch");
    assertThat(mismatch.string("$.method")).isEqualTo("SIMULATOR");
    assertThat(till.post("/api/v1/sales", pushCart(null, intent)).expect(422).string("$.reason"))
        .isEqualTo("payment_mismatch");
    // Its own sale is paid; refused attempts used nothing.
    sell(key, pushCart(key, intent)).expect(201);
    // Used once: another sale is refused (the existing reason).
    String third = UUID.randomUUID().toString();
    assertThat(sell(third, pushCart(third, intent)).expect(422).string("$.reason"))
        .isIn("payment_declined", "payment_mismatch");
  }

  @Test
  @DisplayName("a push request sent without a sale key (an older till) pays any sale")
  void pushWithoutSaleKey() {
    simulator();
    String intent =
        till.post(
                "/api/v1/pos/payment-requests",
                """
                {"methodCode":"SIMULATOR","amount":800,"currency":"TZS","phone":"+255754000111"}
                """)
            .expect(201)
            .string("$.data.uid");
    till.get("/api/v1/pos/payment-requests/" + intent).expect(200);
    String key = UUID.randomUUID().toString();
    sell(key, pushCart(key, intent)).expect(201);
    String again = UUID.randomUUID().toString();
    assertThat(sell(again, pushCart(again, intent)).expect(422).string("$.reason"))
        .isEqualTo("payment_declined");
  }

  // ------------------------------------------------------- 3. problem members on every path

  @Test
  @DisplayName("a refusal at the edge carries its members, as one from a handler does")
  void membersAtTheEdge() {
    // session_idle is refused by the edge filter, before any handler.
    lastUsed(16);
    Response idle = admin.get("/api/v1/settings").expect(401);
    assertThat(idle.header("Content-Type")).startsWith("application/problem+json");
    DocumentContext problem = idle.json();
    assertThat(problem.read("$.reason", String.class)).isEqualTo("session_idle");
    assertThat(problem.read("$.idleMinutes", Integer.class)).isEqualTo(15);
    assertThat(problem.read("$.status", Integer.class)).isEqualTo(401);
    assertThat(problem.read("$.type", String.class)).isEqualTo("/problems/session_idle");
    assertThat(problem.read("$.title", String.class)).isEqualTo("Unauthenticated");
    assertThat(problem.read("$.correlationId", String.class)).isNotBlank();
    assertThat(problem.read("$.detail", String.class))
        .isEqualTo("You were signed out after 15 minutes without using the app; sign in again.");
  }

  @Test
  @DisplayName("server_busy (Batch 7) is 503 with Retry-After and the standard members")
  void serverBusyShape() throws Exception {
    InFlightLimit cap =
        new InFlightLimit(
            1,
            0,
            2,
            new StaticListableBeanFactory()
                .getBeanProvider(io.micrometer.core.instrument.MeterRegistry.class));
    CountDownLatch inside = new CountDownLatch(1);
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
    ExecutorService threads = Executors.newSingleThreadExecutor();
    try {
      Future<?> held = threads.submit(() -> filter(cap, holding));
      assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
      MockHttpServletResponse refused = filter(cap, (q, r) -> {});
      assertThat(refused.getStatus()).isEqualTo(503);
      assertThat(refused.getHeader("Retry-After")).isEqualTo("2");
      assertThat(refused.getContentType()).startsWith("application/problem+json");
      Map<String, Object> body = JsonPath.parse(refused.getContentAsString()).read("$");
      assertThat(body)
          .containsEntry("type", "/problems/server_busy")
          .containsEntry("title", "Service unavailable")
          .containsEntry("status", 503)
          .containsEntry("detail", "The server is busy with other requests; retry in a moment.")
          .containsEntry("reason", "server_busy")
          .containsEntry("retryAfterSeconds", 2)
          .containsEntry("correlationId", refused.getHeader("X-Correlation-Id"));
      release.countDown();
      held.get(10, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      threads.shutdownNow();
    }
  }

  // ----------------------------------------------------------------- 4. suspension kind (UX-8)

  @Test
  @DisplayName(
      "a dispute: the membership and the refusal carry its kind and the hold, not the note")
  void disputeKind() {
    root.post(
            "/api/v1/platform/tenants/" + tenant + "/suspend",
            "{\"kind\":\"DISPUTE\",\"reason\":\"Court order 123/2026, Kisutu\"}")
        .expect(200);
    Response refused = admin.get("/api/v1/customers").expect(403);
    assertThat(refused.string("$.reason")).isEqualTo("tenant_suspended");
    assertThat(refused.string("$.suspensionKind")).isEqualTo("DISPUTE");
    assertThat(refused.read("$.legalHold", Boolean.class)).isTrue();
    assertThat(refused.body()).doesNotContain("Kisutu");

    Response me = anonymous.as(token).get("/api/v1/auth/me").expect(200);
    assertThat(me.string("$.data.memberships[0].tenantStatus")).isEqualTo("SUSPENDED");
    assertThat(me.string("$.data.memberships[0].suspensionKind")).isEqualTo("DISPUTE");
    assertThat(me.read("$.data.memberships[0].legalHold", Boolean.class)).isTrue();
    assertThat(me.body()).doesNotContain("Kisutu");

    root.post("/api/v1/platform/tenants/" + tenant + "/reactivate", "{}").expect(200);
    Response back = anonymous.as(token).get("/api/v1/auth/me").expect(200);
    assertThat(back.json().<Object>read("$.data.memberships[0].suspensionKind")).isNull();
    assertThat(back.read("$.data.memberships[0].legalHold", Boolean.class)).isFalse();
  }

  @Test
  @DisplayName("unpaid: the admin's read-only refusal says UNPAID; a closed pharmacy says closed")
  void unpaidAndClosed() {
    root.post(
            "/api/v1/platform/tenants/" + tenant + "/suspend",
            "{\"kind\":\"UNPAID\",\"reason\":\"Invoice INV-9 overdue\"}")
        .expect(200);
    Response readOnly =
        admin.post("/api/v1/customers", "{\"name\":\"Asha\",\"phone\":\"+255700000011\"}");
    readOnly.expect(403);
    assertThat(readOnly.string("$.reason")).isEqualTo("tenant_suspended_read_only");
    assertThat(readOnly.string("$.suspensionKind")).isEqualTo("UNPAID");
    assertThat(readOnly.read("$.legalHold", Boolean.class)).isFalse();
    assertThat(readOnly.body()).doesNotContain("INV-9");
    assertThat(
            anonymous
                .as(token)
                .get("/api/v1/auth/me")
                .expect(200)
                .string("$.data.memberships[0].suspensionKind"))
        .isEqualTo("UNPAID");

    root.post("/api/v1/platform/tenants/" + tenant + "/reactivate", "{}").expect(200);
    root.post("/api/v1/platform/tenants/" + tenant + "/close", "{\"reason\":\"Owner retired\"}")
        .expect(200);
    Response closed =
        admin.post("/api/v1/customers", "{\"name\":\"Asha\",\"phone\":\"+255700000011\"}");
    closed.expect(403);
    assertThat(closed.string("$.reason")).isEqualTo("tenant_closed");
    assertThat(closed.json().<Object>read("$.suspensionKind")).isNull();
    assertThat(closed.read("$.legalHold", Boolean.class)).isFalse();
    assertThat(closed.body()).doesNotContain("retired");
  }

  // ------------------------------------------------------------------- 3b. value members

  @Test
  @DisplayName("refusals name their values as members: numbers as numbers, dates as ISO text")
  void valueMembers() {
    String key = UUID.randomUUID().toString();
    DocumentContext stock = sell(key, cart(key, product, 80, "64000")).expect(422).json();
    assertThat(stock.read("$.reason", String.class)).isEqualTo("insufficient_stock");
    assertThat(stock.read("$.product", String.class)).isEqualTo("ORS Sachet");
    assertThat(stock.read("$.available", Integer.class)).isEqualTo(50);
    assertThat(stock.read("$.requested", Integer.class)).isEqualTo(80);
    assertThat(stock.read("$.field", String.class)).isEqualTo("lines[0].quantity");
    assertThat(stock.read("$.detail", String.class))
        .isEqualTo("Only 50 of ORS Sachet are in stock at this branch.");

    DocumentContext paid = sell(key, cart(key, product, 2, "1000.5")).expect(422).json();
    assertThat(paid.read("$.reason", String.class)).isEqualTo("payment_short");
    assertThat(paid.read("$.paid", Object.class)).isInstanceOf(Number.class);
    assertThat(paid.read("$.paid", Double.class)).isEqualTo(1000.5);
    assertThat(paid.read("$.total", Double.class)).isEqualTo(1600.0);

    DocumentContext idle = admin.put("/api/v1/settings", "{\"idleMinutes\":4}").expect(422).json();
    assertThat(idle.read("$.reason", String.class)).isEqualTo("invalid_idle_minutes");
    assertThat(idle.read("$.min", Integer.class)).isEqualTo(5);
    assertThat(idle.read("$.max", Integer.class)).isEqualTo(60);

    LocalDate tomorrow = LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam")).plusDays(2);
    DocumentContext future =
        till.post(
                "/api/v1/expenses",
                """
                {"category":"RENT","description":"Next month","amount":100,"paymentMethod":"CASH",
                 "expenseDate":"%s"}
                """
                    .formatted(tomorrow))
            .expect(422)
            .json();
    assertThat(future.read("$.reason", String.class)).isEqualTo("future_date");
    assertThat(future.read("$.date", String.class)).isEqualTo(tomorrow.toString());

    // product_inactive: the product was taken off sale.
    String gone = product("Old syrup", 5);
    deactivate(gone, "Old syrup");
    DocumentContext inactive = sell(key, cart(key, gone, 1, "800")).expect(422).json();
    assertThat(inactive.read("$.reason", String.class)).isEqualTo("product_inactive");
    assertThat(inactive.read("$.product", String.class)).isEqualTo("Old syrup");
  }

  // ------------------------------------------------------------- 5. POS products by uid

  @Test
  @DisplayName("GET /pos/products?uids= reads those products as the search shows them, in order")
  void productsByUid() {
    String second = product("Paracetamol 500mg", 7);
    String gone = product("Old syrup", 3);
    deactivate(gone, "Old syrup");
    Response page =
        till.get(
                "/api/v1/pos/products?uids=%s,%s,%s,%s"
                    .formatted(second, UUID.randomUUID(), product, gone))
            .expect(200);
    assertThat(page.json().<List<String>>read("$.data.items[*].productUid"))
        .containsExactly(second, product);
    assertThat(page.json().<List<Integer>>read("$.data.items[*].quantityOnHand"))
        .containsExactly(7, 50);
    assertThat(page.json().<Object>read("$.data.nextCursor")).isNull();

    // The same shape as the search.
    Map<String, Object> searched =
        till.get("/api/v1/pos/products?q=Paracetamol").expect(200).json().read("$.data.items[0]");
    Map<String, Object> byUid = page.json().read("$.data.items[0]");
    assertThat(byUid).isEqualTo(searched);

    List<String> many = new ArrayList<>();
    for (int i = 0; i < 101; i++) {
      many.add(UUID.randomUUID().toString());
    }
    DocumentContext tooMany =
        till.get("/api/v1/pos/products?uids=" + String.join(",", many)).expect(422).json();
    assertThat(tooMany.read("$.reason", String.class)).isEqualTo("too_many_uids");
    assertThat(tooMany.read("$.max", Integer.class)).isEqualTo(100);
    till.get("/api/v1/pos/products?uids=not-a-uid").expect(422);
  }

  // ------------------------------------------------------------------------------- helpers

  private String product(String name, int quantity) {
    String uid =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"%s","genericName":"%s","dosageForm":"POWDER",
                 "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
                 "sellingPrice":800,"costPrice":300,"taxRatePercent":0}
                """
                    .formatted(name, name))
            .expect(201)
            .string("$.data.uid");
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"B-1","expiryDate":"%s","quantity":%d,"unitCost":300}
            """
                .formatted(uid, LocalDate.now().plusYears(2), quantity))
        .expect(201);
    return uid;
  }

  private void deactivate(String uid, String name) {
    long version =
        admin.get("/api/v1/products/" + uid).expect(200).read("$.data.version", Long.class);
    admin
        .put(
            "/api/v1/products/" + uid,
            """
            {"name":"%s","genericName":"%s","dosageForm":"POWDER",
             "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
             "sellingPrice":800,"costPrice":300,"taxRatePercent":0,"active":false,"version":%d}
            """
                .formatted(name, name, version))
        .expect(200);
  }

  private static String cart(String key, String product, int quantity, String cash) {
    return """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":%d}],
         "payments":[{"method":"CASH","amount":%s}]}
        """
        .formatted(key, product, quantity, cash);
  }

  private String pushCart(String key, String intent) {
    return """
        {%s"lines":[{"productUid":"%s","quantity":1}],
         "payments":[{"method":"MOBILE_MONEY","amount":800,"channel":"SIMULATOR",
                      "reference":"%s"}]}
        """
        .formatted(key == null ? "" : "\"idempotencyKey\":\"" + key + "\",", product, intent);
  }

  /** As the till sends a sale: the key in the body and in the Idempotency-Key header. */
  private Response sell(String key, String body) {
    return till.post("/api/v1/sales", body, "Idempotency-Key", key);
  }

  private int onHand(String productUid) {
    List<Integer> left =
        till.get("/api/v1/stock")
            .expect(200)
            .json()
            .read("$.data.items[?(@.productUid == '" + productUid + "')].quantity");
    return left.stream().mapToInt(Integer::intValue).sum();
  }

  private void simulator() {
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
  }

  /** As if the admin last used the app {@code minutes} ago. */
  private void lastUsed(int minutes) {
    new JdbcTemplate(dataSource)
        .update(
            "update access.sessions set active_at = now() - make_interval(mins => ?)"
                + " where revoked_at is null"
                + " and user_uid = (select uid from access.users where email = ?)",
            minutes,
            email);
  }

  private static MockHttpServletResponse filter(InFlightLimit cap, FilterChain chain) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/sales");
    MockHttpServletResponse response = new MockHttpServletResponse();
    try {
      cap.doFilter(request, response, chain);
    } catch (Exception failure) {
      throw new IllegalStateException(failure);
    }
    return response;
  }
}
