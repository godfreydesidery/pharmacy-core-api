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
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * A pharmacy decides whether its selling prices include VAT. A new pharmacy starts with prices that
 * include it (shelf prices in Tanzania are shown with VAT in them); the till then charges the shelf
 * price and the books take the VAT out of it. Switched off, the VAT is added on top, as before.
 */
class PricesIncludeTaxTest extends IntegrationTest {

  private Api admin;
  private Api till;
  private String lotion;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "VT" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"VAT %s","legalName":"VAT %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenant, branch);
    lotion =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Body Lotion 200ml","genericName":"Lotion","dosageForm":"LOTION",
                 "schedule":"OTC","productType":"COSMETIC","trackingMode":"BATCH",
                 "sellingPrice":3540,"costPrice":2000,"taxRatePercent":18}
                """)
            .expect(201)
            .string("$.data.uid");
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"LOT-1","expiryDate":"%s","quantity":20,"unitCost":2000}
            """
                .formatted(lotion, LocalDate.now().plusYears(2)))
        .expect(201);
  }

  private Response sell(int quantity, double paid) {
    return till.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":%d}],
             "payments":[{"method":"CASH","amount":%s}]}
            """
                .formatted(UUID.randomUUID(), lotion, quantity, paid))
        .expect(201);
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

  @Test
  @DisplayName("a new pharmacy's prices include VAT: the till charges the shelf price")
  void newPharmacyIncludesVat() {
    assertThat(
            admin
                .get("/api/v1/settings")
                .expect(200)
                .read("$.data.pricesIncludeTax", Boolean.class))
        .isTrue();

    Response sale = sell(1, 3540);

    assertThat(sale.read("$.data.total", Double.class)).isEqualTo(3540.0);
    assertThat(sale.read("$.data.taxTotal", Double.class)).isEqualTo(540.0);
    assertThat(sale.read("$.data.lines[0].lineTotal", Double.class)).isEqualTo(3540.0);
    // The books: revenue without the VAT, and the VAT owed to TRA.
    eventually(() -> Math.abs(ledger("4000") - 3000.0) < 0.001);
    assertThat(ledger("2100")).isEqualTo(540.0);
  }

  @Test
  @DisplayName("switched off, the VAT is added on top as before")
  void switchedOffAddsVatOnTop() {
    Response changed = admin.put("/api/v1/settings", "{\"pricesIncludeTax\":false}").expect(200);
    assertThat(changed.read("$.data.pricesIncludeTax", Boolean.class)).isFalse();

    Response sale = sell(1, 4177.2);

    assertThat(sale.read("$.data.total", Double.class)).isEqualTo(4177.2);
    assertThat(sale.read("$.data.taxTotal", Double.class)).isEqualTo(637.2);
  }

  @Test
  @DisplayName("saving other settings keeps the VAT rule")
  void otherSettingsKeepTheRule() {
    admin.put("/api/v1/settings", "{\"receiptFooter\":\"Asante\"}").expect(200);

    assertThat(
            admin
                .get("/api/v1/settings")
                .expect(200)
                .read("$.data.pricesIncludeTax", Boolean.class))
        .isTrue();
  }
}
