package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tz.co.pharmacy.application.checkout.ports.inbound.Checkout;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutCommand;
import tz.co.pharmacy.application.checkout.ports.inbound.CheckoutLine;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentCommand;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.inbound.SaleView;
import tz.co.pharmacy.platform.kernel.Actor;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.StatementCounter;

/**
 * The checkout path's cost in round trips to PostgreSQL (PERF-1, PERF-7, PERF-10, PERF-11 of the
 * adversarial review of 23 Nov 2026). The sale is made through the {@link Checkout} port on the
 * test's own thread, so {@link StatementCounter} sees exactly the statements of one checkout: its
 * transactions, their {@code set_config} and every query and write, but not the reactions that run
 * after commit on other threads (the ledger, reporting, webhooks).
 */
class CheckoutPerformanceTest extends IntegrationTest {

  /**
   * The most statements a 3-line cash sale may send. Measured on 23 Nov 2026: 71 before Batch 7
   * (the settings read 7 times, a pack lookup per line twice, an audit insert per ledger row), 46
   * after; the budget leaves a little room for the modules the sale goes through.
   */
  static final int THREE_LINE_BUDGET = 50;

  @Autowired Checkout checkout;

  private Api admin;
  private Api till;
  private UUID tenant;
  private UUID branch;
  private final List<UUID> products = new ArrayList<>();

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "CP" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenant =
        UUID.fromString(
            root.post(
                    "/api/v1/platform/tenants",
                    """
                    {"code":"%s","name":"Perf %s","legalName":"Perf %s Ltd","country":"TZ",
                     "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                     "headOffice":{"code":"HQ","name":"Head Office"},
                     "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                    """
                        .formatted(code, code, code, email))
                .expect(201)
                .string("$.data.uid"));
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant.toString(), null);
    branch =
        UUID.fromString(
            admin
                .get("/api/v1/auth/me")
                .expect(200)
                .string("$.data.memberships[0].branches[0].uid"));
    till = admin.in(tenant.toString(), branch.toString());
    for (String name : List.of("Paracetamol 500mg", "Amoxicillin 250mg", "ORS Sachet")) {
      UUID product = product(name, 1000);
      till.post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"B-%s","expiryDate":"%s","quantity":100,
               "unitCost":400}
              """
                  .formatted(product, name.substring(0, 3), LocalDate.now().plusYears(2)))
          .expect(201);
      products.add(product);
    }
  }

  private UUID product(String name, int price) {
    return product(name, price, 18);
  }

  private UUID product(String name, int price, int taxRatePercent) {
    return UUID.fromString(
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
                 "productType":"MEDICINE","trackingMode":"BATCH","sellingPrice":%d,
                 "costPrice":400,"taxRatePercent":%d}
                """
                    .formatted(name, name.split(" ")[0], price, taxRatePercent))
            .expect(201)
            .string("$.data.uid"));
  }

  /** The cashier's context, as the edge sets it for a request at the branch. */
  private <T> T atTill(Supplier<T> work) {
    UUID cashier = UUID.randomUUID();
    ExecutionContext.Frame frame =
        new ExecutionContext.Frame(
            Actor.user(cashier, "Perf Cashier", "perf.cashier@test.local"),
            UUID.randomUUID().toString(),
            tenant,
            branch,
            Set.of("sales.create", "sales.discount", "sales.credit", "sales.price_plan"),
            null,
            false);
    return ExecutionContext.callAs(
        frame,
        () -> {
          TenantContext.enter(tenant, cashier);
          try {
            return work.get();
          } finally {
            TenantContext.leave();
          }
        });
  }

  private CheckoutCommand cashSale(UUID customer, int... quantities) {
    List<CheckoutLine> lines = new ArrayList<>();
    for (int i = 0; i < quantities.length; i++) {
      lines.add(new CheckoutLine(products.get(i), quantities[i], null));
    }
    return new CheckoutCommand(
        UUID.randomUUID().toString(),
        customer,
        BigDecimal.ZERO,
        lines,
        List.of(new PaymentCommand(PaymentMethod.CASH, new BigDecimal("100000"), null)),
        null);
  }

  private static void print(String what, StatementCounter.Recorded<?> recorded) {
    System.out.println(
        "CheckoutPerformanceTest: " + what + ": " + recorded.count() + " statements");
    for (int i = 0; i < recorded.statements().size(); i++) {
      System.out.println("  " + (i + 1) + ". " + recorded.statements().get(i));
    }
  }

  @Test
  @DisplayName("a 3-line cash sale stays under its statement budget and reads the settings once")
  void threeLineSale() {
    // A first sale warms the caches every sale shares (the tenant's time zone).
    atTill(() -> checkout.checkout(cashSale(null, 1)));

    StatementCounter.Recorded<SaleView> sale =
        StatementCounter.record(() -> atTill(() -> checkout.checkout(cashSale(null, 2, 3, 1))));
    print("3-line cash sale", sale);

    assertThat(sale.result().lines()).hasSize(3);
    assertThat(sale.count()).isLessThanOrEqualTo(THREE_LINE_BUDGET);
    assertThat(sale.matching("from tenancy.settings"))
        .as("the pharmacy's settings, read once per checkout")
        .hasSize(1);
  }

  @Test
  @DisplayName(
      "a plan-priced sale reads only its products' plan prices; price lists come without items")
  void planPricing() {
    List<UUID> others = new ArrayList<>();
    for (int i = 0; i < 5; i++) {
      others.add(product("Filler " + i, 500));
    }
    StringBuilder items = new StringBuilder();
    for (UUID product : products) {
      items.append("{\"productUid\":\"%s\",\"minQuantity\":1,\"price\":900},".formatted(product));
      items.append("{\"productUid\":\"%s\",\"minQuantity\":10,\"price\":850},".formatted(product));
    }
    for (UUID product : others) {
      items.append("{\"productUid\":\"%s\",\"minQuantity\":1,\"price\":450},".formatted(product));
    }
    String plan =
        admin
            .post(
                "/api/v1/price-lists",
                """
                {"name":"Wholesale","customerType":"WHOLESALE","items":[%s]}
                """
                    .formatted(items.substring(0, items.length() - 1)))
            .expect(201)
            .string("$.data.uid");
    UUID clinic =
        UUID.fromString(
            admin
                .post(
                    "/api/v1/customers",
                    "{\"name\":\"City Clinic\",\"phone\":\"+255700300400\",\"type\":\"WHOLESALE\"}")
                .expect(201)
                .string("$.data.uid"));

    StatementCounter.Recorded<SaleView> sale =
        StatementCounter.record(() -> atTill(() -> checkout.checkout(cashSale(clinic, 2, 10))));
    print("plan-priced 2-line sale", sale);

    assertThat(sale.result().priceListName()).isEqualTo("Wholesale");
    assertThat(sale.result().lines())
        .extracting(l -> l.unitPrice().stripTrailingZeros().toPlainString())
        .containsExactly("900", "850");
    List<String> planReads = sale.matching("price_list_items");
    assertThat(planReads).isNotEmpty();
    assertThat(planReads)
        .as("plan prices are looked up by product, never the whole plan")
        .allSatisfy(sql -> assertThat(sql.toLowerCase()).containsPattern("product_uid\\s*(in|=)"));

    // The plans come without their items; a plan's items are served a page at a time.
    Api.Response lists = admin.get("/api/v1/price-lists").expect(200);
    assertThat((Object) lists.json().read("$.data[0].items")).isNull();
    assertThat((Integer) lists.json().read("$.data[0].itemCount")).isEqualTo(11);
    Api.Response page = admin.get("/api/v1/pricing-plans/" + plan + "/items?limit=4").expect(200);
    assertThat((List<Object>) page.json().read("$.data.items")).hasSize(4);
    // A save that does not name the items keeps them (a client that never loaded them).
    admin
        .put(
            "/api/v1/price-lists/" + plan,
            "{\"name\":\"Wholesale\",\"customerType\":\"WHOLESALE\",\"active\":true,\"items\":[]}")
        .expect(200);
    assertThat(
            (Integer)
                admin.get("/api/v1/price-lists").expect(200).json().read("$.data[0].itemCount"))
        .isEqualTo(11);
  }

  @Test
  @DisplayName(
      "a declined push payment takes no stock and no number; the branch's numbers have no gap")
  void declinedPayment() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
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
    UUID vitamin = product("Vitamin C", 1000, 0);
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"V-1","expiryDate":"%s","quantity":10,"unitCost":400}
            """
                .formatted(vitamin, LocalDate.now().plusYears(1)))
        .expect(201);
    String request =
        till.post(
                "/api/v1/pos/payment-requests",
                """
                {"methodCode":"SIMULATOR","amount":1000,"currency":"TZS","phone":"+255754000111"}
                """)
            .expect(201)
            .string("$.data.uid");
    String pushed =
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
         "payments":[{"method":"MOBILE_MONEY","amount":1000,"channel":"SIMULATOR",
                      "reference":"%s"}]}
        """;
    String first =
        till.post("/api/v1/sales", pushed.formatted(UUID.randomUUID(), vitamin, request))
            .expect(201)
            .string("$.data.number");
    // The same confirmed request cannot pay a second sale: refused, and nothing is taken.
    assertThat(
            till.post("/api/v1/sales", pushed.formatted(UUID.randomUUID(), vitamin, request))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("payment_declined");
    List<Integer> left =
        till.get("/api/v1/stock")
            .expect(200)
            .json()
            .read("$.data.items[?(@.productUid == '" + vitamin + "')].quantity");
    assertThat(left).containsExactly(9);
    String next =
        till.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
                 "payments":[{"method":"CASH","amount":2000}]}
                """
                    .formatted(UUID.randomUUID(), vitamin))
            .expect(201)
            .string("$.data.number");
    assertThat(sequence(next)).isEqualTo(sequence(first) + 1);
  }

  private static int sequence(String number) {
    return Integer.parseInt(number.substring(number.lastIndexOf('-') + 1));
  }

  @Test
  @DisplayName("the audit trail holds the sale, not the rows of the append-only ledgers")
  void auditTrail() {
    String sale =
        till.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1},
                 {"productUid":"%s","quantity":2}],"payments":[{"method":"CASH","amount":5000}]}
                """
                    .formatted(UUID.randomUUID(), products.get(0), products.get(1)))
            .expect(201)
            .string("$.data.uid");
    // The ledger posts the sale after commit; wait for its entry.
    Api.eventually(
        () -> admin.get("/api/v1/journal?limit=50").expect(200).body().contains("\"4000\""));

    assertThat(audited("Sale")).as("the sale document").contains(sale);
    for (String ledgerRow : List.of("SaleLine", "SalePayment", "Movement", "JournalLine")) {
      assertThat(audited(ledgerRow)).as(ledgerRow + " rows in the audit trail").isEmpty();
    }
    assertThat(audited("JournalEntry")).as("the journal entry document").isNotEmpty();
  }

  private List<String> audited(String entityType) {
    return admin
        .get("/api/v1/audit?entityType=" + entityType + "&limit=100")
        .expect(200)
        .json()
        .read("$.data.items[*].entityUid");
  }
}
