package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Receiving more of a batch at another cost (a wholesaler re-prices the same lot): the batch takes
 * the weighted average, and the ledger's inventory keeps matching the stock at cost, to the cent.
 * Found by the simulated market, where a 4% price rise made every pharmacy's books drift.
 */
class BatchCostingTest extends IntegrationTest {

  private Api admin;
  private Api till;
  private String supplier;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "BC" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Costing %s","legalName":"Costing %s Ltd","country":"TZ",
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
            .post("/api/v1/suppliers", "{\"name\":\"Lake Zone Drugs\",\"paymentTerms\":\"NET_30\"}")
            .expect(201)
            .string("$.data.uid");
  }

  private String product(String name, int cost) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
             "sellingPrice":%d,"costPrice":%d,"taxRatePercent":0}
            """
                .formatted(name, name, cost * 2, cost))
        .expect(201)
        .string("$.data.uid");
  }

  private void receive(
      String product, String batch, int quantity, String unitCost, String invoice) {
    till.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"%s","lines":[
              {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%s}]}
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

  private double batchCost(String product, String batch) {
    List<Object> costs =
        till.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[?(@.batchNumber == '" + batch + "')].unitCost");
    return Double.parseDouble(costs.getFirst().toString());
  }

  private double stockAtCost() {
    List<Object> values =
        till.get("/api/v1/stock?limit=100").expect(200).json().read("$.data.items[*].costValue");
    return values.stream().mapToDouble(v -> Double.parseDouble(v.toString())).sum();
  }

  private double ledgerInventory() {
    List<Object> balance =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '1200')].balance");
    return Double.parseDouble(balance.getFirst().toString());
  }

  @Test
  @DisplayName(
      "a re-priced lot: the batch costs the average and the ledger equals the stock at cost")
  void averagesTheBatch() {
    String albendazole = product("Albendazole 400mg", 200);
    receive(albendazole, "ALB-2611", 10, "200", "INV-1");
    receive(albendazole, "ALB-2611", 10, "210", "INV-2");

    assertThat(batchCost(albendazole, "ALB-2611")).isEqualTo(205.0);
    eventually(() -> Math.abs(ledgerInventory() - 4_100.0) < 0.001);
    assertThat(stockAtCost()).isEqualTo(4_100.0);
  }

  @Test
  @DisplayName("an average between cents: the rounding is posted and the books still match")
  void postsTheRounding() {
    String cream = product("Hydrocortisone Cream 1%", 100);
    receive(cream, "HC-2607", 3, "100", "INV-3");
    receive(cream, "HC-2607", 4, "101", "INV-4");

    // (300 + 404) / 7 = 100.571… → 100.57; the stock is worth 703.99, one cent below what was paid.
    assertThat(batchCost(cream, "HC-2607")).isEqualTo(100.57);
    assertThat(stockAtCost()).isEqualTo(703.99);
    eventually(() -> Math.abs(ledgerInventory() - 703.99) < 0.001);
  }
}
