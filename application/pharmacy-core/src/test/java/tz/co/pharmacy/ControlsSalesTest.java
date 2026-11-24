package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
import tz.co.pharmacy.support.Staff;

/**
 * The owner's business controls at the till (Batch 4 of the adversarial review; decisions of 23 Nov
 * 2026): the return window and refund approvals, where returned goods go (a prescription medicine
 * never back on the shelf), the open shift, a shift's cash difference, deep discounts and
 * promotions, and what a sale line keeps of how it was sold.
 */
class ControlsSalesTest extends IntegrationTest {

  private static final String CLERK_PASSWORD = "Pharm#2026x";
  private static final String ADMIN_PASSWORD = "Admin#2026x";

  @Autowired DataSource dataSource;

  private Api anonymous;
  private Api admin;
  private Api hq;
  private Api clerk;
  private String tenantUid;
  private String adminEmail;
  private String supplier;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "CS" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    adminEmail = code.toLowerCase() + "@test.local";
    tenantUid =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Controls %s","legalName":"Controls %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Neema Owner","email":"%s","password":"%s"}}
                """
                    .formatted(code, code, code, adminEmail, ADMIN_PASSWORD))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(adminEmail, ADMIN_PASSWORD)).in(tenantUid, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    hq = admin.in(tenantUid, branch);
    Staff.Login login =
        Staff.login(admin, anonymous, "Juma Manager", "BRANCH_MANAGER", CLERK_PASSWORD);
    clerk = anonymous.as(anonymous.signIn(login.signIn(), CLERK_PASSWORD)).in(tenantUid, branch);
    supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Lake Zone Drugs\",\"paymentTerms\":\"NET_30\"}")
            .expect(201)
            .string("$.data.uid");
  }

  // ---------------------------------------------------------------- returns (FRD-4)

  @Test
  @DisplayName("after the return window a manager approves the return (LATE_RETURN)")
  void lateReturnNeedsApproval() {
    String product = product("Paracetamol 500mg", "OTC", 1_000, 400);
    receive(product, 20, 400);
    Response recent = sell(clerk, product, 1, null);
    Response old = sell(clerk, product, 1, null);
    backdate(old.string("$.data.uid"), 8);

    // Within the 7 days: no approval.
    returnLine(clerk, recent, 1, null).expect(201);

    Response refused = returnLine(clerk, old, 1, null);
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("LATE_RETURN");
    assertThat(money(refused, "$.amount")).isEqualTo(1_000.0);
    String token = approve("LATE_RETURN", 1_000);
    returnLine(clerk, old, 1, null, "X-Approval", token).expect(201);

    // A longer window, set in Settings, lets an 8-day-old sale come back without one.
    admin.put("/api/v1/settings", "{\"returnWindowDays\":14}").expect(200);
    Response another = sell(clerk, product, 1, null);
    backdate(another.string("$.data.uid"), 8);
    returnLine(clerk, another, 1, null).expect(201);
  }

  @Test
  @DisplayName("a refund above the approval limit needs a manager (REFUND); below it none")
  void refundAboveTheLimit() {
    String product = product("Glucometer kit", "OTC", 60_000, 30_000);
    receive(product, 5, 30_000);
    Response sale = sell(clerk, product, 2, null);

    Response refused = returnLine(clerk, sale, 1, null);
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("REFUND");
    assertThat(money(refused, "$.amount")).isEqualTo(60_000.0);
    // An approval for less does not cover it.
    String small = approve("REFUND", 50_000);
    assertThat(returnLine(clerk, sale, 1, null, "X-Approval", small).expect(403).string("$.reason"))
        .isEqualTo("approval_invalid");
    returnLine(clerk, sale, 1, null, "X-Approval", approve("REFUND", 60_000)).expect(201);

    admin.put("/api/v1/settings", "{\"approvalLimit\":100000}").expect(200);
    returnLine(clerk, sale, 1, null).expect(201);
  }

  @Test
  @DisplayName(
      "returned goods go to the shelf, are written off, or wait in quarantine; a prescription"
          + " medicine never goes back on the shelf, by its class when sold")
  void dispositions() {
    String otc = product("Vitamin C 1000mg", "OTC", 1_000, 400);
    String rx = product("Amoxicillin 500mg", "PRESCRIPTION", 2_000, 800);
    receive(otc, 20, 400);
    receive(rx, 20, 800);
    BigDecimal drift = settledDrift();

    // The line keeps the class the medicine had when sold.
    Response rxSale =
        sell(
            clerk,
            rx,
            4,
            """
            {"prescriberName":"Dr Asha","prescriberRegistration":"MCT-2201",
             "patientName":"Rehema Juma","prescriptionDate":"%s"}
            """
                .formatted(LocalDate.now().minusDays(2)));
    assertThat(rxSale.string("$.data.lines[0].schedule")).isEqualTo("PRESCRIPTION");
    assertThat(onHand(rx)).isEqualTo(16);
    // Reclassified OTC afterwards (straight in the table: the rule reads the sale, not this).
    sql("update catalogue.products set schedule = 'OTC' where uid = ?::uuid", rx);

    Response shelf = returnLine(clerk, rxSale, 1, "SHELF");
    assertThat(shelf.expect(422).string("$.reason")).isEqualTo("restock_not_allowed");
    assertThat(shelf.string("$.field")).isEqualTo("lines[0].disposition");

    // No disposition: a prescription medicine is held in quarantine, out of stock.
    Response held = returnLine(clerk, rxSale, 1, null).expect(201);
    assertThat(held.string("$.data.lines[0].disposition")).isEqualTo("QUARANTINE");
    assertThat(held.read("$.data.lines[0].heldQuantity", Integer.class)).isEqualTo(1);
    assertThat(onHand(rx)).isEqualTo(16);
    List<String> waiting =
        clerk.get("/api/v1/sale-returns/held").expect(200).json().read("$.data[*].number");
    assertThat(waiting).contains(held.string("$.data.number"));
    String release =
        "/api/v1/sales/returns/%s/lines/%s/release"
            .formatted(held.string("$.data.uid"), held.string("$.data.lines[0].uid"));
    assertThat(clerk.post(release, "{\"outcome\":\"SHELF\"}").expect(422).string("$.reason"))
        .isEqualTo("restock_not_allowed");
    Response released = clerk.post(release, "{\"outcome\":\"DAMAGED\"}").expect(200);
    assertThat(released.read("$.data.lines[0].heldQuantity", Integer.class)).isZero();
    assertThat(released.string("$.data.lines[0].releasedOutcome")).isEqualTo("DAMAGED");
    assertThat(onHand(rx)).isEqualTo(16);
    assertThat(clerk.post(release, "{\"outcome\":\"DAMAGED\"}").expect(422).string("$.reason"))
        .isEqualTo("nothing_held");

    // Written off at once: restocked and adjusted out as damage.
    Response damaged = returnLine(clerk, rxSale, 1, "DAMAGED").expect(201);
    assertThat(damaged.string("$.data.lines[0].disposition")).isEqualTo("DAMAGED");
    assertThat(onHand(rx)).isEqualTo(16);
    List<String> reasons =
        hq.get("/api/v1/stock/movements?productUid=" + rx + "&type=ADJUSTMENT")
            .expect(200)
            .json()
            .read("$.data.items[*].reasonCode");
    assertThat(reasons).containsExactly("DAMAGE", "DAMAGE");

    // An OTC medicine goes back on the shelf by default; quarantined, it can be released to it.
    Response otcSale = sell(clerk, otc, 3, null);
    assertThat(otcSale.string("$.data.lines[0].schedule")).isEqualTo("OTC");
    Response back = returnLine(clerk, otcSale, 1, null).expect(201);
    assertThat(back.string("$.data.lines[0].disposition")).isEqualTo("SHELF");
    assertThat(onHand(otc)).isEqualTo(18);
    Response aside = returnLine(clerk, otcSale, 1, "QUARANTINE").expect(201);
    assertThat(onHand(otc)).isEqualTo(18);
    clerk
        .post(
            "/api/v1/sales/returns/%s/lines/%s/release"
                .formatted(aside.string("$.data.uid"), aside.string("$.data.lines[0].uid")),
            "{\"outcome\":\"SHELF\"}")
        .expect(200);
    assertThat(onHand(otc)).isEqualTo(19);

    // Through all of it the books' inventory (1200) moves with the stock at cost.
    eventually(() -> drift().compareTo(drift) == 0);
    assertThat(drift()).isEqualByComparingTo(drift);
  }

  // ---------------------------------------------------------------- open shift (FRD-7)

  @Test
  @DisplayName(
      "with the setting on, selling, refunding and issuing a voucher need an open shift; a close"
          + " short or over by more than the limit needs a manager")
  void openShift() {
    String product = product("ORS sachet", "OTC", 1_000, 300);
    receive(product, 20, 300);
    Response before = sell(clerk, product, 1, null);

    admin.put("/api/v1/settings", "{\"requireOpenShift\":true}").expect(200);
    assertThat(sellRaw(clerk, product, 1, null).expect(409).string("$.reason"))
        .isEqualTo("shift_required");
    assertThat(returnLine(clerk, before, 1, null).expect(409).string("$.reason"))
        .isEqualTo("shift_required");
    assertThat(
            hq.post("/api/v1/vouchers", "{\"value\":5000,\"paidBy\":\"CASH\"}")
                .expect(409)
                .string("$.reason"))
        .isEqualTo("shift_required");

    clerk.post("/api/v1/shifts/open", "{\"openingFloat\":0}").expect(201);
    sell(clerk, product, 1, null);
    returnLine(clerk, before, 1, null).expect(201);

    // 1,000 taken, 1,000 refunded: 0 expected. Counting 60,000 is 60,000 over.
    Response refused = clerk.post("/api/v1/shifts/close", "{\"countedCash\":60000}");
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("SHIFT_VARIANCE");
    assertThat(money(refused, "$.amount")).isEqualTo(60_000.0);
    assertThat(clerk.get("/api/v1/shifts/current").expect(200).string("$.data.status"))
        .isEqualTo("OPEN");
    Response closed =
        clerk
            .post(
                "/api/v1/shifts/close",
                "{\"countedCash\":60000}",
                "X-Approval",
                approve("SHIFT_VARIANCE", 60_000))
            .expect(200);
    assertThat(money(closed, "$.data.variance")).isEqualTo(60_000.0);
    // The till suggests the next float from what was counted at the last close.
    assertThat(money(clerk.get("/api/v1/shifts/last-count").expect(200), "$.data.countedCash"))
        .isEqualTo(60_000.0);

    admin.put("/api/v1/settings", "{\"requireOpenShift\":false}").expect(200);
    sell(clerk, product, 1, null);
  }

  // ---------------------------------------------------------------- discounts (FRD-8)

  @Test
  @DisplayName("a till discount above the maximum needs a manager (DEEP_DISCOUNT)")
  void deepDiscount() {
    String product = product("Cough syrup", "OTC", 10_000, 4_000);
    receive(product, 20, 4_000);
    // Within the 10% maximum: no approval.
    sell(clerk, product, 1, null, "\"discountPercent\":10");

    Response refused = sellRaw(clerk, product, 1, null, "\"discountPercent\":15");
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("DEEP_DISCOUNT");
    assertThat(money(refused, "$.amount")).isEqualTo(15.0);
    Response sold =
        sellRaw(
                clerk,
                product,
                1,
                null,
                "\"discountPercent\":15",
                "X-Approval",
                approve("DEEP_DISCOUNT", 15))
            .expect(201);
    assertThat(money(sold, "$.data.discountTotal")).isEqualTo(1_500.0);

    // Still only for those who may give a discount at all.
    Staff.Login till = Staff.login(admin, anonymous, "Till Cashier", "CASHIER", "Cashier#2026x");
    Api cashier =
        anonymous
            .as(anonymous.signIn(till.signIn(), "Cashier#2026x"))
            .in(tenantUid, branchOf(clerk));
    assertThat(
            sellRaw(cashier, product, 1, null, "\"discountPercent\":15")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("discount_not_allowed");
  }

  @Test
  @DisplayName(
      "a promotion deeper than the maximum needs a manager (DEEP_PROMOTION); the sale line names"
          + " its promotion; deleting one switches it off and keeps it")
  void deepPromotion() {
    String product = product("Zinc tablets", "OTC", 2_000, 800);
    receive(product, 20, 800);
    String body =
        """
        {"name":"Zinc week","percentOff":%d,"productUid":"%s","validFrom":"%s"}
        """;
    Response refused =
        clerk.post("/api/v1/promotions", body.formatted(30, product, LocalDate.now()));
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("approval_required");
    assertThat(refused.string("$.action")).isEqualTo("DEEP_PROMOTION");
    assertThat(money(refused, "$.amount")).isEqualTo(30.0);
    String promotion =
        clerk
            .post(
                "/api/v1/promotions",
                body.formatted(30, product, LocalDate.now()),
                "X-Approval",
                approve("DEEP_PROMOTION", 30))
            .expect(201)
            .string("$.data.uid");
    // One within the maximum needs nothing.
    clerk.post("/api/v1/promotions", body.formatted(5, product, LocalDate.now())).expect(201);

    Response sale = sell(clerk, product, 1, null);
    assertThat(money(sale, "$.data.lines[0].unitPrice")).isEqualTo(1_400.0);
    assertThat(sale.string("$.data.lines[0].promotionUid")).isEqualTo(promotion);
    assertThat(sale.string("$.data.lines[0].promotionName")).isEqualTo("Zinc week");
    assertThat(sale.json().read("$.data.pricePlanSource", Object.class)).isNull();

    Response off = clerk.delete("/api/v1/promotions/" + promotion).expect(200);
    assertThat(off.read("$.data.active", Boolean.class)).isFalse();
    List<Boolean> kept =
        admin
            .get("/api/v1/promotions")
            .expect(200)
            .json()
            .read("$.data[?(@.uid == '" + promotion + "')].active");
    assertThat(kept).containsExactly(false);
    Response again = clerk.get("/api/v1/sales/" + sale.string("$.data.uid")).expect(200);
    assertThat(again.string("$.data.lines[0].promotionName")).isEqualTo("Zinc week");
  }

  // ---------------------------------------------------------------- helpers

  /** A manager (the owner) approves {@code action} up to {@code amount} on the clerk's screen. */
  private String approve(String action, long amount) {
    return clerk
        .post(
            "/api/v1/approvals",
            "{\"action\":\"%s\",\"amount\":%d,\"login\":\"%s\",\"password\":\"%s\"}"
                .formatted(action, amount, adminEmail, ADMIN_PASSWORD))
        .expect(201)
        .string("$.data.token");
  }

  private String product(String name, String schedule, int price, int cost) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"%s",
             "sellingPrice":%d,"costPrice":%d,"taxRatePercent":0}
            """
                .formatted(name, name, schedule, price, cost))
        .expect(201)
        .string("$.data.uid");
  }

  private void receive(String product, int quantity, int cost) {
    hq.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"%s","lines":[
              {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%d}]}
            """
                .formatted(
                    supplier,
                    "INV-" + UUID.randomUUID().toString().substring(0, 8),
                    product,
                    "B-" + UUID.randomUUID().toString().substring(0, 6),
                    LocalDate.now().plusYears(2),
                    quantity,
                    cost))
        .expect(201);
  }

  private static Response sell(
      Api at, String product, int quantity, String prescription, String... extra) {
    return sellRaw(at, product, quantity, prescription, extra).expect(201);
  }

  /** {@code extra}: body members ("discountPercent":15), then header name and value pairs. */
  private static Response sellRaw(
      Api at, String product, int quantity, String prescription, String... extra) {
    String members = "";
    int i = 0;
    while (i < extra.length && extra[i].startsWith("\"")) {
      members += extra[i] + ",";
      i++;
    }
    String[] headers = java.util.Arrays.copyOfRange(extra, i, extra.length);
    return at.post(
        "/api/v1/sales",
        """
        {"idempotencyKey":"%s",%s%s"lines":[{"productUid":"%s","quantity":%d}],
         "payments":[{"method":"CASH","amount":200000}]}
        """
            .formatted(
                UUID.randomUUID(),
                members,
                prescription == null ? "" : "\"prescription\":" + prescription + ",",
                product,
                quantity),
        headers);
  }

  private static Response returnLine(
      Api at, Response sale, int quantity, String disposition, String... headers) {
    return at.post(
        "/api/v1/sales/" + sale.string("$.data.uid") + "/returns",
        """
        {"reason":"Brought back","lines":[{"saleLineUid":"%s","quantity":%d%s}]}
        """
            .formatted(
                sale.string("$.data.lines[0].uid"),
                quantity,
                disposition == null ? "" : ",\"disposition\":\"" + disposition + "\""),
        headers);
  }

  private String branchOf(Api at) {
    return at.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
  }

  private int onHand(String product) {
    List<Object> quantities =
        hq.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[*].quantity");
    return quantities.stream().mapToInt(q -> ((Number) q).intValue()).sum();
  }

  /** Moves the sale {@code days} back, as if it had been sold then. */
  private void backdate(String saleUid, int days) {
    sql(
        "update sales.sales set created_at = ? where uid = ?::uuid",
        Timestamp.from(Instant.now().minus(days, ChronoUnit.DAYS)),
        saleUid);
  }

  /** The ledger's inventory (1200) less the stock at cost, once the postings have caught up. */
  private BigDecimal settledDrift() {
    BigDecimal[] last = {drift()};
    eventually(
        () -> {
          BigDecimal now = drift();
          boolean same = now.compareTo(last[0]) == 0;
          last[0] = now;
          return same && now.signum() == 0;
        });
    return last[0];
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
    return ledger.subtract(
        query(
            "select coalesce(sum(s.quantity * b.unit_cost), 0) from inventory.stock_levels s"
                + " join inventory.batches b on b.id = s.batch_id"));
  }

  /** Runs as the application does: in one transaction scoped to this pharmacy (forced RLS). */
  private void sql(String statement, Object... args) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      scope(connection);
      try (PreparedStatement update = connection.prepareStatement(statement)) {
        for (int i = 0; i < args.length; i++) {
          update.setObject(i + 1, args[i]);
        }
        assertThat(update.executeUpdate()).isEqualTo(1);
      }
      connection.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  private BigDecimal query(String sql) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      scope(connection);
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

  private void scope(Connection connection) throws SQLException {
    try (PreparedStatement scope =
        connection.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
      scope.setString(1, tenantUid);
      scope.execute();
    }
  }

  private static double money(Response response, String path) {
    Object value = response.json().read(path);
    return value instanceof Number number
        ? number.doubleValue()
        : Double.parseDouble((String) value);
  }
}
