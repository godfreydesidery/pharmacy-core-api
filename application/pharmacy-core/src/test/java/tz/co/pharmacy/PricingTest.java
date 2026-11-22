package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/** Price lists, quantity breaks, special prices, branch prices and promotions, at the till. */
class PricingTest extends IntegrationTest {

  @Test
  @DisplayName("the till resolves branch price, list (with breaks), special price and promotion")
  void resolution() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "PR" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Pricing %s","legalName":"Pricing %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    Api hq = admin.in(tenant, branch);

    String a = product(admin, "Amoxicillin 500mg", 1000, 600);
    String b = product(admin, "Vitamin C", 500, 200);
    for (String p : new String[] {a, b}) {
      hq.post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"B-%s","expiryDate":"%s","quantity":100,"unitCost":100}
              """
                  .formatted(p, p.substring(0, 4), LocalDate.now().plusYears(1)))
          .expect(201);
    }
    String wholesale = customer(admin, "City Clinic", "WHOLESALE");
    String special = customer(admin, "Mama Upendo", "RETAIL");

    admin
        .post(
            "/api/v1/price-lists",
            """
            {"name":"Wholesale","customerType":"WHOLESALE","adjustmentPercent":-10,
             "items":[{"productUid":"%s","minQuantity":10,"price":800}]}
            """
                .formatted(a))
        .expect(201);
    admin
        .post(
            "/api/v1/price-lists",
            """
            {"name":"Mama Upendo special","customerUid":"%s","customerName":"Mama Upendo",
             "items":[{"productUid":"%s","price":950}]}
            """
                .formatted(special, a))
        .expect(201);
    admin
        .put(
            "/api/v1/branch-prices",
            "{\"productUid\":\"%s\",\"branchUid\":\"%s\",\"price\":550}".formatted(b, branch))
        .expect(200);
    admin
        .post(
            "/api/v1/promotions",
            """
            {"name":"Vitamin week","percentOff":20,"productUid":"%s","validFrom":"%s","validTo":"%s"}
            """
                .formatted(b, LocalDate.now(), LocalDate.now().plusDays(7)))
        .expect(201);

    Response walkIn = quote(hq, null, a, 1);
    assertThat(walkIn.read("$.data[0].unitPrice", Double.class)).isEqualTo(1000.0);
    assertThat(walkIn.string("$.data[0].source")).isEqualTo("RETAIL");

    Response five = quote(hq, wholesale, a, 5);
    assertThat(five.read("$.data[0].unitPrice", Double.class)).isEqualTo(900.0);
    assertThat(five.string("$.data[0].source")).isEqualTo("LIST");
    Response twelve = quote(hq, wholesale, a, 12);
    assertThat(twelve.read("$.data[0].unitPrice", Double.class)).isEqualTo(800.0);

    Response own = quote(hq, special, a, 1);
    assertThat(own.read("$.data[0].unitPrice", Double.class)).isEqualTo(950.0);
    assertThat(own.string("$.data[0].label")).isEqualTo("Mama Upendo special");

    // Branch 550, wholesale −10% = 495; the plan's price stands (no promotion on top of a plan).
    Response promo = quote(hq, wholesale, b, 1);
    assertThat(promo.read("$.data[0].unitPrice", Double.class)).isEqualTo(495.0);
    assertThat(promo.string("$.data[0].source")).isEqualTo("LIST");
    assertThat(quote(hq, null, b, 1).read("$.data[0].unitPrice", Double.class)).isEqualTo(440.0);

    // A promotion with no end date runs until switched off.
    String c = product(admin, "Zinc tablets", 2000, 1000);
    Response open =
        admin
            .post(
                "/api/v1/promotions",
                """
                {"name":"Zinc always","percentOff":10,"productUid":"%s","validFrom":"%s"}
                """
                    .formatted(c, LocalDate.now().minusDays(1)))
            .expect(201);
    assertThat(open.json().read("$.data.validTo", Object.class)).isNull();
    Response zinc = quote(hq, null, c, 1);
    assertThat(zinc.read("$.data[0].unitPrice", Double.class)).isEqualTo(1800.0);
    assertThat(zinc.string("$.data[0].source")).isEqualTo("PROMOTION");

    // Checkout charges what was quoted: 12 × 800.
    Response sale =
        hq.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","customerUid":"%s","lines":[{"productUid":"%s","quantity":12}],
                 "payments":[{"method":"CASH","amount":9600}]}
                """
                    .formatted(UUID.randomUUID(), wholesale, a))
            .expect(201);
    assertThat(sale.read("$.data.total", Double.class)).isEqualTo(9600.0);
  }

  private static Response quote(Api till, String customer, String product, int quantity) {
    return till.post(
            "/api/v1/pos/prices",
            """
            {"customerUid":%s,"lines":[{"productUid":"%s","quantity":%d}]}
            """
                .formatted(customer == null ? "null" : "\"" + customer + "\"", product, quantity))
        .expect(200);
  }

  private static String product(Api admin, String name, int price, int cost) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
             "sellingPrice":%d,"costPrice":%d}
            """
                .formatted(name, name, price, cost))
        .expect(201)
        .string("$.data.uid");
  }

  private static String customer(Api admin, String name, String type) {
    return admin
        .post(
            "/api/v1/customers",
            "{\"name\":\"%s\",\"phone\":\"+255700000009\",\"type\":\"%s\",\"creditLimit\":100000}"
                .formatted(name, type))
        .expect(201)
        .string("$.data.uid");
  }
}
