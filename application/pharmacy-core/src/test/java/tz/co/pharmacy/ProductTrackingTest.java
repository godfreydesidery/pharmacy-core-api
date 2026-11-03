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
 * A pharmacy sells more than medicines: a wheelchair is tracked unit by unit by serial number and
 * sold with its warranty; gloves are tracked by batch without expiry and sold first in, first out;
 * crutches by quantity alone.
 */
class ProductTrackingTest extends IntegrationTest {

  @Test
  @DisplayName("equipment by serial number, supplies first in first out, quantity-only goods")
  void trackingModes() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "EQ" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Equip %s","legalName":"Equip %s Ltd","country":"TZ",
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

    // The first read of the categories seeds the default tree.
    Response categories = admin.get("/api/v1/categories").expect(200);
    List<String> paths = categories.json().read("$.data[*].path");
    assertThat(paths).contains("Medical Equipment › Mobility Aids", "Medicines › Analgesics");
    String mobility =
        categories
            .json()
            .read("$.data[?(@.name == 'Mobility Aids')].uid", List.class)
            .getFirst()
            .toString();

    String wheelchair =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Wheelchair Standard 18in","productType":"MEDICAL_EQUIPMENT",
                 "categoryUid":"%s","brand":"Invacare","model":"Action 1","warrantyMonths":12,
                 "sellingPrice":450000,"costPrice":320000}
                """
                    .formatted(mobility))
            .expect(201)
            .json()
            .read("$.data.uid", String.class);
    Response product = admin.get("/api/v1/products/" + wheelchair).expect(200);
    assertThat(product.string("$.data.trackingMode")).isEqualTo("SERIAL");
    assertThat(product.read("$.data.requiresSerialAtSale", Boolean.class)).isTrue();
    assertThat(product.read("$.data.dosageForm", String.class)).isNull();

    // A medicine still needs its clinical fields.
    Response noForm =
        admin.post(
            "/api/v1/products",
            "{\"name\":\"Paracetamol\",\"genericName\":\"Paracetamol\",\"sellingPrice\":1,"
                + "\"costPrice\":1}");
    noForm.expect(422);
    assertThat(noForm.string("$.reason")).isEqualTo("invalid_dosage_form");

    String gloves =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Surgical Gloves Box","productType":"MEDICAL_SUPPLY","unitOfMeasure":"BOX",
                 "sellingPrice":18000,"costPrice":11000}
                """)
            .expect(201)
            .string("$.data.uid");
    String crutches =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Crutches (Pair)","productType":"MEDICAL_EQUIPMENT","trackingMode":"QUANTITY",
                 "unitOfMeasure":"PAIR","sellingPrice":45000,"costPrice":28000}
                """)
            .expect(201)
            .string("$.data.uid");
    String supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Mobility Supplies\",\"paymentTerms\":\"COD\"}")
            .expect(201)
            .string("$.data.uid");

    // A serial line needs one serial number per unit.
    Response mismatch =
        hq.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","lines":[
              {"productUid":"%s","quantity":2,"unitCost":320000,"serialNumbers":["WCH-1"]}]}
            """
                .formatted(supplier, wheelchair));
    mismatch.expect(422);
    assertThat(mismatch.string("$.reason")).isEqualTo("serials_mismatch");

    hq.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","lines":[
              {"productUid":"%s","quantity":2,"unitCost":320000,"serialNumbers":["WCH-1","WCH-2"]},
              {"productUid":"%s","batchNumber":"GLV-OLD","quantity":5,"unitCost":11000},
              {"productUid":"%s","quantity":10,"unitCost":28000}]}
            """
                .formatted(supplier, wheelchair, gloves, crutches))
        .expect(201);
    hq.post(
            "/api/v1/goods-receipts",
            """
            {"supplierUid":"%s","lines":[
              {"productUid":"%s","batchNumber":"GLV-NEW","quantity":5,"unitCost":12000}]}
            """
                .formatted(supplier, gloves))
        .expect(201);

    List<String> inStock =
        hq.get("/api/v1/stock/" + wheelchair + "/serials?status=IN_STOCK")
            .expect(200)
            .json()
            .read("$.data[*].serialNumber");
    assertThat(inStock).containsExactly("WCH-1", "WCH-2");

    // A wheelchair asks for its serial number at the till.
    Response noSerial =
        hq.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
             "payments":[{"method":"CASH","amount":450000}]}
            """
                .formatted(UUID.randomUUID(), wheelchair));
    noSerial.expect(422);
    assertThat(noSerial.string("$.reason")).isEqualTo("serial_required");

    Response sale =
        hq.post(
                "/api/v1/sales",
                """
                {"idempotencyKey":"%s","lines":[
                   {"productUid":"%s","quantity":1,"serialNumbers":["WCH-2"]},
                   {"productUid":"%s","quantity":6},
                   {"productUid":"%s","quantity":2}],
                 "payments":[{"method":"CASH","amount":2000000}]}
                """
                    .formatted(UUID.randomUUID(), wheelchair, gloves, crutches))
            .expect(201);
    List<String> soldSerials = sale.json().read("$.data.lines[0].serialNumbers");
    assertThat(soldSerials).containsExactly("WCH-2");
    assertThat(sale.string("$.data.lines[0].warrantyEndsOn"))
        .isEqualTo(LocalDate.now().plusMonths(12).minusDays(1).toString());
    // Gloves without expiry: the older batch goes first.
    List<String> gloveBatches =
        sale.json().read("$.data.lines[?(@.productName == 'Surgical Gloves Box')].batchNumber");
    assertThat(gloveBatches).containsExactly("GLV-OLD", "GLV-NEW");

    List<String> stillInStock =
        hq.get("/api/v1/stock/" + wheelchair + "/serials?status=IN_STOCK")
            .expect(200)
            .json()
            .read("$.data[*].serialNumber");
    assertThat(stillInStock).containsExactly("WCH-1");

    // The wheelchair comes back: WCH-2 is in stock again.
    String saleUid = sale.string("$.data.uid");
    String wheelchairLine = sale.string("$.data.lines[0].uid");
    hq.post(
            "/api/v1/sales/" + saleUid + "/returns",
            """
            {"reason":"Wrong size","refundMethod":"CASH","lines":[
              {"saleLineUid":"%s","quantity":1,"serialNumbers":["WCH-2"]}]}
            """
                .formatted(wheelchairLine))
        .expect(201);
    List<String> afterReturn =
        hq.get("/api/v1/stock/" + wheelchair + "/serials?status=IN_STOCK")
            .expect(200)
            .json()
            .read("$.data[*].serialNumber");
    assertThat(afterReturn).containsExactly("WCH-1", "WCH-2");

    // The stock list knows each product's type and unit; equipment can be filtered.
    Response equipment = hq.get("/api/v1/stock?productType=MEDICAL_EQUIPMENT").expect(200);
    List<String> units = equipment.json().read("$.data.items[*].unitOfMeasure");
    assertThat(units).containsExactlyInAnyOrder("PIECE", "PAIR");

    // With crutches in stock the tracking mode is locked; other fields still change.
    String crutchesBody =
        """
        {"name":"Crutches (Pair)","productType":"MEDICAL_EQUIPMENT","trackingMode":"%s",
         "unitOfMeasure":"PAIR","sellingPrice":%s,"costPrice":28000,"version":%d}
        """;
    long version =
        admin.get("/api/v1/products/" + crutches).expect(200).read("$.data.version", Long.class);
    Response locked =
        admin.put("/api/v1/products/" + crutches, crutchesBody.formatted("BATCH", 45000, version));
    locked.expect(422);
    assertThat(locked.string("$.reason")).isEqualTo("tracking_mode_locked");
    assertThat(locked.string("$.detail")).contains("8 units");
    admin
        .put("/api/v1/products/" + crutches, crutchesBody.formatted("QUANTITY", 47000, version))
        .expect(200);

    // A product with no stock anywhere may switch.
    String bandage =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Elastic Bandage","productType":"MEDICAL_SUPPLY","trackingMode":"QUANTITY",
                 "sellingPrice":3000,"costPrice":1500}
                """)
            .expect(201)
            .string("$.data.uid");
    admin
        .put(
            "/api/v1/products/" + bandage,
            """
            {"name":"Elastic Bandage","productType":"MEDICAL_SUPPLY","trackingMode":"BATCH",
             "sellingPrice":3000,"costPrice":1500,"version":%d}
            """
                .formatted(
                    admin
                        .get("/api/v1/products/" + bandage)
                        .expect(200)
                        .read("$.data.version", Long.class)))
        .expect(200);
  }
}
