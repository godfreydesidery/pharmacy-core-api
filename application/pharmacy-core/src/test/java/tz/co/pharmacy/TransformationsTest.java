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

/** Break bulk at the till, repacking into own packs, and compounding from a formula. */
class TransformationsTest extends IntegrationTest {

  @Test
  @DisplayName("a box opens into strips at the till; repack and compound keep batch and expiry")
  void transformations() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "TR" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Repack %s","legalName":"Repack %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    Api hq = admin.in(tenant, branch);
    LocalDate boxExpiry = LocalDate.now().plusMonths(18);

    String box = product(admin, "Paracetamol box of 10 strips", 4000, 2000);
    String strip = product(admin, "Paracetamol strip", 500, 200);
    opening(hq, box, "BX-1", boxExpiry, 5, 2000);
    // Opening stock is recorded while the branch is set up, before it sells (FRD-11).
    String bulk = product(admin, "ORS bulk sachets", 300, 100);
    String pack = product(admin, "ORS own pack of 5", 1600, 600);
    opening(hq, bulk, "ORS-1", LocalDate.now().plusMonths(6), 100, 100);
    String zinc = product(admin, "Zinc oxide 10g", 800, 400);
    String calamine = product(admin, "Calamine powder 10g", 900, 500);
    String lotion = product(admin, "Calamine lotion 100ml", 6000, 0);
    opening(hq, zinc, "ZN-1", LocalDate.now().plusMonths(12), 40, 400);
    opening(hq, calamine, "CL-1", LocalDate.now().plusMonths(9), 40, 500);
    admin
        .put(
            "/api/v1/pack-links",
            "{\"packProductUid\":\"%s\",\"unitProductUid\":\"%s\",\"unitsPerPack\":10}"
                .formatted(box, strip))
        .expect(200);

    // Three strips with none loose: one box is opened at the till.
    hq.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":3}],
             "payments":[{"method":"CASH","amount":1500}]}
            """
                .formatted(UUID.randomUUID(), strip))
        .expect(201);
    Response list = hq.get("/api/v1/stock/transformations").expect(200);
    assertThat(list.string("$.data.items[0].type")).isEqualTo("BREAK_BULK");
    assertThat(list.read("$.data.items[0].outputQuantity", Integer.class)).isEqualTo(10);
    assertThat(list.string("$.data.items[0].outputExpiryDate")).isEqualTo(boxExpiry.toString());
    assertThat(list.read("$.data.items[0].inputs[0].quantity", Integer.class)).isEqualTo(1);
    assertThat(list.read("$.data.items[0].outputUnitCost", Double.class)).isEqualTo(200.0);

    // Repack 50 sachets of bulk ORS into 10 own packs; an expiry beyond the input is refused.
    // FRD-2: a repack follows the pack link: five sachets fill one own pack.
    admin
        .put(
            "/api/v1/pack-links",
            "{\"packProductUid\":\"%s\",\"unitProductUid\":\"%s\",\"unitsPerPack\":5}"
                .formatted(pack, bulk))
        .expect(200);
    String repack =
        """
        {"type":"REPACK","inputs":[{"productUid":"%s","quantity":50}],
         "output":{"productUid":"%s","quantity":10,"expiryDate":"%s"},"note":"Own label"}
        """;
    Response late =
        hq.post(
            "/api/v1/stock/transformations",
            repack.formatted(bulk, pack, LocalDate.now().plusYears(2)));
    assertThat(late.expect(422).string("$.reason")).isEqualTo("expiry_after_inputs");
    Response packed =
        hq.post(
                "/api/v1/stock/transformations",
                repack.formatted(bulk, pack, LocalDate.now().plusMonths(3)))
            .expect(201);
    assertThat(packed.read("$.data.outputUnitCost", Double.class)).isEqualTo(500.0);

    // Compound 5 bottles of calamine lotion from its formula.
    admin
        .put(
            "/api/v1/products/" + lotion + "/formula",
            """
            [{"ingredientUid":"%s","quantity":2},{"ingredientUid":"%s","quantity":3}]
            """
                .formatted(zinc, calamine))
        .expect(200);
    List<Object> formula =
        admin.get("/api/v1/products/" + lotion + "/formula").expect(200).json().read("$.data");
    assertThat(formula).hasSize(2);
    Response compounded =
        hq.post(
                "/api/v1/stock/transformations",
                """
                {"type":"COMPOUND","inputs":[{"productUid":"%s","quantity":10},
                 {"productUid":"%s","quantity":15}],"output":{"productUid":"%s","quantity":5}}
                """
                    .formatted(zinc, calamine, lotion))
            .expect(201);
    // (10 × 400 + 15 × 500) / 5 = 2,300; expiry follows the calamine (the earliest input).
    assertThat(compounded.read("$.data.outputUnitCost", Double.class)).isEqualTo(2300.0);
    assertThat(compounded.string("$.data.outputExpiryDate"))
        .isEqualTo(LocalDate.now().plusMonths(9).toString());

    // Inputs left out come from the formula, scaled by the quantity made: 2 bottles need 4 + 6.
    Response scaled =
        hq.post(
                "/api/v1/stock/transformations",
                """
                {"type":"COMPOUND","output":{"productUid":"%s","quantity":2}}
                """
                    .formatted(lotion))
            .expect(201);
    assertThat(
            scaled.read("$.data.inputs[?(@.productName == 'Zinc oxide 10g')].quantity", List.class))
        .containsExactly(4);
    assertThat(scaled.read("$.data.outputUnitCost", Double.class)).isEqualTo(2300.0);
    // Inputs given must cover the formula: 1 zinc oxide for 2 bottles is too little.
    Response short_ =
        hq.post(
            "/api/v1/stock/transformations",
            """
            {"type":"COMPOUND","inputs":[{"productUid":"%s","quantity":1},
             {"productUid":"%s","quantity":6}],"output":{"productUid":"%s","quantity":2}}
            """
                .formatted(zinc, calamine, lotion));
    assertThat(short_.expect(422).string("$.reason")).isEqualTo("inputs_below_formula");
  }

  @Test
  @DisplayName(
      "the till lists strips that can be opened from boxes, and sells them with none loose")
  void tillListsOpenableUnits() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "TB" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
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
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    Api hq = admin.in(tenant, branch);
    String box = product(admin, "Cetirizine box of 10 strips", 9000, 5000);
    String strip = product(admin, "Cetirizine strip", 1000, 500);
    opening(hq, box, "CT-1", LocalDate.now().plusMonths(12), 5, 5000);
    admin
        .put(
            "/api/v1/pack-links",
            "{\"packProductUid\":\"%s\",\"unitProductUid\":\"%s\",\"unitsPerPack\":10}"
                .formatted(box, strip))
        .expect(200);

    Response listed = hq.get("/api/v1/pos/products?q=Cetirizine%20strip").expect(200);
    assertThat(listed.read("$.data.items[0].quantityOnHand", Integer.class)).isZero();
    assertThat(listed.read("$.data.items[0].openableUnits", Integer.class)).isEqualTo(50);
    Response boxes = hq.get("/api/v1/pos/products?q=Cetirizine%20box").expect(200);
    assertThat(boxes.read("$.data.items[0].openableUnits", Integer.class)).isZero();

    hq.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":12}],
             "payments":[{"method":"CASH","amount":12000}]}
            """
                .formatted(UUID.randomUUID(), strip))
        .expect(201);
    // Two boxes opened: 20 strips, 12 sold, 8 loose; 3 boxes (30 strips) still openable.
    Response after = hq.get("/api/v1/pos/products?q=Cetirizine%20strip").expect(200);
    assertThat(after.read("$.data.items[0].quantityOnHand", Integer.class)).isEqualTo(8);
    assertThat(after.read("$.data.items[0].openableUnits", Integer.class)).isEqualTo(30);
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

  private static void opening(
      Api till, String product, String batch, LocalDate expiry, int quantity, int cost) {
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":%d}
            """
                .formatted(product, batch, expiry, quantity, cost))
        .expect(201);
  }
}
