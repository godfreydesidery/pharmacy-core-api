package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * The spine of the system, end to end over HTTP: the root administrator registers two pharmacies;
 * one receives stock in two batches, sells across them earliest-expiry first, and the ledger and
 * dashboard catch up from the events; the other pharmacy sees none of it.
 */
class PharmacyFlowTest extends IntegrationTest {

  @Test
  @DisplayName(
      "root admin onboards tenants; FEFO sale; ledger and dashboard follow; tenants isolated")
  void spine() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));

    String suffix = UUID.randomUUID().toString().substring(0, 4).toUpperCase().replace("-", "");
    Tenant afya = onboard(root, "AF" + suffix, "afya-" + suffix + "@test.local");
    Tenant tiba = onboard(root, "TB" + suffix, "tiba-" + suffix + "@test.local");

    Api admin = anonymous.as(anonymous.signIn(afya.adminEmail, "Admin#2026x")).in(afya.uid, null);
    Response me = admin.get("/api/v1/auth/me").expect(200);
    assertThat(me.read("$.data.memberships[0].tenantUid", String.class)).isEqualTo(afya.uid);
    String branch = me.read("$.data.memberships[0].branches[0].uid", String.class);
    Api hq = admin.in(afya.uid, branch);

    String category =
        admin
            .post("/api/v1/categories", "{\"name\":\"Analgesic\",\"description\":\"Pain\"}")
            .expect(201)
            .string("$.data.uid");
    String product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Paracetamol 500mg","genericName":"Paracetamol","sku":"PCM500",
                 "categoryUid":"%s","dosageForm":"TABLET","strength":"500 mg","schedule":"OTC",
                 "sellingPrice":1500.00,"costPrice":800.00,"taxRatePercent":0,"reorderLevel":5,
                 "storage":"ROOM_TEMPERATURE"}
                """
                    .formatted(category))
            .expect(201)
            .string("$.data.uid");
    String supplier =
        admin
            .post(
                "/api/v1/suppliers", "{\"name\":\"MedSupply Africa\",\"paymentTerms\":\"NET_30\"}")
            .expect(201)
            .string("$.data.uid");

    LocalDate soon = LocalDate.now().plusDays(60);
    LocalDate later = LocalDate.now().plusDays(400);
    hq.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","supplierInvoiceNumber":"SI-1","lines":[
              {"productUid":"%s","batchNumber":"LATE-1","expiryDate":"%s","quantity":10,"unitCost":800.00},
              {"productUid":"%s","batchNumber":"SOON-1","expiryDate":"%s","quantity":4,"unitCost":750.00}]}
            """
                .formatted(supplier, product, later, product, soon))
        .expect(201);

    Response sale =
        hq.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":6}],
                 "payments":[{"method":"CASH","amount":10000.00}]}
                """
                    .formatted(UUID.randomUUID(), product))
            .expect(201);
    List<String> batchesSold = sale.json().read("$.data.lines[*].batchNumber");
    assertThat(batchesSold).containsExactly("SOON-1", "LATE-1");
    assertThat(sale.string("$.data.total")).isIn("9000.0", "9000.00", "9000");
    assertThat(sale.read("$.data.changeDue", Double.class)).isEqualTo(1000.0);

    Response batches = hq.get("/api/v1/stock/" + product + "/batches").expect(200);
    assertThat(batches.json().read("$.data[*].quantity", List.class)).containsExactly(8);

    // A prescription-only product cannot be sold without prescription details.
    String antibiotic =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Amoxicillin 250mg","genericName":"Amoxicillin","dosageForm":"CAPSULE",
                 "schedule":"PRESCRIPTION","sellingPrice":3800.00,"costPrice":2100.00,
                 "storage":"ROOM_TEMPERATURE"}
                """)
            .expect(201)
            .string("$.data.uid");
    hq.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"AMX-1","expiryDate":"%s","quantity":5,"unitCost":2100.00}
            """
                .formatted(antibiotic, later))
        .expect(201);
    Response refused =
        hq.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
             "payments":[{"method":"CASH","amount":5000.00}]}
            """
                .formatted(UUID.randomUUID(), antibiotic));
    refused.expect(422);
    assertThat(refused.string("$.reason")).isEqualTo("prescription_required");

    // The ledger and the dashboard catch up from the events.
    Api.eventually(
        () ->
            admin
                    .get("/api/v1/journal")
                    .expect(200)
                    .json()
                    .read("$.data.items.length()", Integer.class)
                >= 2);
    Api.eventually(
        () ->
            admin
                    .get("/api/v1/dashboard?days=7")
                    .expect(200)
                    .read("$.data.sales.value", Double.class)
                >= 9000.0);

    // The other tenant sees nothing of Afya's, and cannot act in Afya.
    Api tibaAdmin =
        anonymous.as(anonymous.signIn(tiba.adminEmail, "Admin#2026x")).in(tiba.uid, null);
    assertThat(
            tibaAdmin
                .get("/api/v1/products")
                .expect(200)
                .read("$.data.items.length()", Integer.class))
        .isZero();
    Response crossTenant = tibaAdmin.in(afya.uid, null).get("/api/v1/products");
    crossTenant.expect(403);

    // The root administrator may act in any tenant.
    assertThat(
            root.in(afya.uid, null)
                .get("/api/v1/products")
                .expect(200)
                .read("$.data.items.length()", Integer.class))
        .isEqualTo(2);
  }

  @Test
  @DisplayName("a request without a session, or with an unknown token, is refused")
  void unauthenticated() {
    api().get("/api/v1/auth/me").expect(401);
    api().as("pcs_not-a-token").get("/api/v1/auth/me").expect(401);
    api().get("/actuator/health").expect(200);
  }

  private Tenant onboard(Api root, String code, String adminEmail) {
    Response tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Pharmacy %s","legalName":"Pharmacy %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office","address":"Samora Ave","phone":"+255700000000"},
                 "admin":{"name":"Admin %s","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, code, adminEmail))
            .expect(201);
    return new Tenant(tenant.string("$.data.uid"), adminEmail);
  }

  private record Tenant(String uid, String adminEmail) {}
}
