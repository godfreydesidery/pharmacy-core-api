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

/** Returns to suppliers, batch recalls (quarantine) and the destruction register. */
class StockOperationsTest extends IntegrationTest {

  @Test
  @DisplayName("a supplier return lowers stock and payables; a recall quarantines; destruction")
  void stockOperations() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SO" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Ops %s","legalName":"Ops %s Ltd","country":"TZ",
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

    String product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Ibuprofen 400mg","genericName":"Ibuprofen","dosageForm":"TABLET",
                 "schedule":"OTC","sellingPrice":1000,"costPrice":400}
                """)
            .expect(201)
            .string("$.data.uid");
    String supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Ops Supplier\",\"paymentTerms\":\"NET_30\"}")
            .expect(201)
            .string("$.data.uid");
    String receipt =
        hq.post(
                "/api/v1/goods-receipts",
                """
                {"supplierUid":"%s","supplierInvoiceNumber":"OPS-1","lines":[
                  {"productUid":"%s","batchNumber":"A-1","expiryDate":"%s","quantity":20,"unitCost":400},
                  {"productUid":"%s","batchNumber":"B-1","expiryDate":"%s","quantity":10,"unitCost":500}]}
                """
                    .formatted(
                        supplier,
                        product,
                        LocalDate.now().plusMonths(6),
                        product,
                        LocalDate.now().plusMonths(12)))
            .expect(201)
            .string("$.data.uid");
    Api.eventually(() -> payable(admin) == 13_000.0);
    String batchA = batch(hq, product, "A-1");

    // 1. Return 5 of batch A to the supplier: stock 15 left, payables down by 2,000.
    String returnBody =
        """
        {"supplierUid":"%s","goodsReceiptUid":"%s","reason":"Damaged in transit",
         "lines":[{"productUid":"%s","batchUid":"%s","quantity":%d}]}
        """;
    Response tooMany =
        hq.post(
                "/api/v1/supplier-returns",
                returnBody.formatted(supplier, receipt, product, batchA, 100))
            .expect(422);
    assertThat(tooMany.string("$.reason")).isEqualTo("insufficient_stock");
    assertThat(tooMany.string("$.field")).isEqualTo("lines[0].quantity");
    // A receipt that never held batch A cannot be cited for it.
    String other =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Cough Syrup 100ml","genericName":"Dextromethorphan","dosageForm":"SYRUP",
                 "schedule":"OTC","sellingPrice":3000,"costPrice":100}
                """)
            .expect(201)
            .string("$.data.uid");
    String otherReceipt =
        hq.post(
                "/api/v1/goods-receipts",
                """
                {"supplierUid":"%s","supplierInvoiceNumber":"OPS-2","lines":[
                  {"productUid":"%s","batchNumber":"C-1","expiryDate":"%s","quantity":1,"unitCost":100}]}
                """
                    .formatted(supplier, other, LocalDate.now().plusMonths(9)))
            .expect(201)
            .string("$.data.uid");
    Response wrongReceipt =
        hq.post(
                "/api/v1/supplier-returns",
                returnBody.formatted(supplier, otherReceipt, product, batchA, 5))
            .expect(422);
    assertThat(wrongReceipt.string("$.reason")).isEqualTo("batch_not_on_receipt");
    assertThat(wrongReceipt.string("$.field")).isEqualTo("lines[0].batchUid");
    Response returned =
        hq.post(
                "/api/v1/supplier-returns",
                returnBody.formatted(supplier, receipt, product, batchA, 5))
            .expect(201);
    assertThat(returned.string("$.data.number")).startsWith("RTS");
    // The debit note names who recorded it, not their sign-in email.
    assertThat(returned.string("$.data.createdByName")).isEqualTo("Admin");
    assertThat(returned.string("$.data.createdBy")).isEqualTo(email);
    assertThat(returned.read("$.data.total", Double.class)).isEqualTo(2_000.0);
    assertThat(returned.string("$.data.goodsReceiptNumber")).startsWith("GRN");
    assertThat(quantity(hq, product, "A-1")).isEqualTo(15);
    Api.eventually(() -> payable(admin) == 11_100.0);
    // What is owed to the supplier: 13,000 + 100 received − 2,000 returned = 11,100.
    Response balances = admin.get("/api/v1/suppliers/balances").expect(200);
    assertThat(balances.string("$.data[0].supplierUid")).isEqualTo(supplier);
    assertThat(balances.read("$.data[0].received", Double.class)).isEqualTo(13_100.0);
    assertThat(balances.read("$.data[0].returned", Double.class)).isEqualTo(2_000.0);
    assertThat(balances.read("$.data[0].balance", Double.class)).isEqualTo(11_100.0);
    assertThat(balances.string("$.data[0].lastActivity")).isNotNull();
    // A payment of 1,100 brings it to 10,000; the statement runs receipts, return, payment.
    admin
        .post(
            "/api/v1/finance/supplier-payments",
            """
            {"supplierUid":"%s","amount":1100,"currency":"TZS","paymentMethod":"BANK_TRANSFER",
             "reference":"CRDB-778"}
            """
                .formatted(supplier))
        .expect(201);
    Response statement = admin.get("/api/v1/suppliers/" + supplier + "/statement").expect(200);
    List<String> types = statement.json().read("$.data.lines[*].type");
    assertThat(types).containsExactlyInAnyOrder("RECEIPT", "RECEIPT", "RETURN", "PAYMENT");
    assertThat(statement.read("$.data.balance", Double.class)).isEqualTo(10_000.0);
    assertThat(
            admin
                .get("/api/v1/suppliers/balances")
                .expect(200)
                .read("$.data[0].paid", Double.class))
        .isEqualTo(1_100.0);
    assertThat(
            hq.get("/api/v1/supplier-returns").expect(200).read("$.data.length()", Integer.class))
        .isEqualTo(1);
    hq.get("/api/v1/supplier-returns/" + returned.string("$.data.uid")).expect(200);

    // 2. Recall: a customer bought 3 of batch A; quarantined, A is no longer sold.
    String customer =
        admin
            .post(
                "/api/v1/customers",
                "{\"name\":\"Asha Mrisho\",\"phone\":\"+255700000077\",\"type\":\"RETAIL\"}")
            .expect(201)
            .string("$.data.uid");
    assertThat(sell(hq, product, 3, customer)).isEqualTo("A-1");
    Response recall = hq.get("/api/v1/recalls/batches/" + batchA).expect(200);
    assertThat(recall.string("$.data.sales[0].customerPhone")).isEqualTo("+255700000077");
    assertThat(recall.read("$.data.sales[0].quantity", Integer.class)).isEqualTo(3);
    assertThat(recall.read("$.data.unitsWithCustomers", Integer.class)).isEqualTo(3);
    assertThat(recall.read("$.data.batch.onHand", Integer.class)).isEqualTo(12);
    List<Object> batches =
        hq.get("/api/v1/recalls/products/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data");
    assertThat(batches).hasSize(2);

    hq.post("/api/v1/recalls/batches/" + batchA + "/quarantine", "{}").expect(422);
    Response quarantined =
        hq.post(
                "/api/v1/recalls/batches/" + batchA + "/quarantine",
                "{\"reason\":\"Manufacturer recall MR-12\"}")
            .expect(200);
    assertThat(quarantined.read("$.data.batch.quarantined", Boolean.class)).isTrue();
    assertThat(sell(hq, product, 2, null)).isEqualTo("B-1");
    // B has 8 left; 9 would need the quarantined batch: refused as a recall, not a shortage.
    String nine =
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":%d}],
         "payments":[{"method":"CASH","amount":%d}]}
        """;
    Response recalled =
        hq.post("/api/v1/sales", nine.formatted(UUID.randomUUID(), product, 9, 9000)).expect(422);
    assertThat(recalled.string("$.reason")).isEqualTo("batch_quarantined");
    assertThat(recalled.string("$.detail")).contains("Only 8", "batch A-1", "recall");
    assertThat(recalled.string("$.field")).isEqualTo("lines[0].quantity");
    // More than all of it, quarantined or not, is still a plain shortage.
    assertThat(
            hq.post("/api/v1/sales", nine.formatted(UUID.randomUUID(), product, 100, 100000))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("insufficient_stock");
    // The till sees the recalled units.
    assertThat(
            hq.get("/api/v1/pos/products?q=Ibuprofen")
                .expect(200)
                .read("$.data.items[0].quarantinedUnits", Integer.class))
        .isEqualTo(12);
    hq.post("/api/v1/recalls/batches/" + batchA + "/release", "{}").expect(200);
    assertThat(sell(hq, product, 1, null)).isEqualTo("A-1");

    // 3. Destroy 2 of batch A before a witness: written off, on the register with a certificate.
    String destruction =
        """
        {"method":"Incineration","witnessName":"Dr. Juma Ally","witnessTitle":"TMDA inspector",
         "certificateNumber":"CERT-77","lines":[{"batchUid":"%s","quantity":%d,"reason":"DAMAGED"}]}
        """;
    Response short_ =
        hq.post("/api/v1/destructions", destruction.formatted(batchA, 500)).expect(422);
    assertThat(short_.string("$.reason")).isEqualTo("insufficient_stock");
    assertThat(short_.string("$.field")).isEqualTo("lines[0].quantity");
    // Batch A expires in six months: it cannot be destroyed as expired.
    Response notExpired =
        hq.post(
                "/api/v1/destructions",
                destruction.formatted(batchA, 1).replace("\"DAMAGED\"", "\"EXPIRED\""))
            .expect(422);
    assertThat(notExpired.string("$.reason")).isEqualTo("batch_not_expired");
    assertThat(notExpired.string("$.field")).isEqualTo("lines[0].reason");
    Response destroyed =
        hq.post("/api/v1/destructions", destruction.formatted(batchA, 2)).expect(201);
    assertThat(destroyed.string("$.data.number")).startsWith("DST");
    assertThat(destroyed.string("$.data.createdByName")).isEqualTo("Admin");
    assertThat(destroyed.read("$.data.totalCost", Double.class)).isEqualTo(800.0);
    assertThat(destroyed.string("$.data.branchName")).isEqualTo("Head Office");
    assertThat(quantity(hq, product, "A-1")).isEqualTo(9);
    assertThat(hq.get("/api/v1/destructions").expect(200).string("$.data[0].witnessName"))
        .isEqualTo("Dr. Juma Ally");
    hq.get("/api/v1/destructions/" + destroyed.string("$.data.uid")).expect(200);
  }

  private static double payable(Api admin) {
    List<Object> balance =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '2000')].balance", List.class);
    return balance.isEmpty() ? 0 : ((Number) balance.get(0)).doubleValue();
  }

  private static String batch(Api hq, String product, String number) {
    List<String> uids =
        hq.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[?(@.batchNumber == '" + number + "')].uid", List.class);
    return uids.get(0);
  }

  private static int quantity(Api hq, String product, String number) {
    List<Integer> found =
        hq.get("/api/v1/stock/" + product + "/batches")
            .expect(200)
            .json()
            .read("$.data[?(@.batchNumber == '" + number + "')].quantity", List.class);
    return found.isEmpty() ? 0 : found.get(0);
  }

  /** Sells {@code quantity} for cash and returns the batch number of the first line. */
  private static String sell(Api hq, String product, int quantity, String customer) {
    return hq.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s",%s"lines":[{"productUid":"%s","quantity":%d}],
             "payments":[{"method":"CASH","amount":%d}]}
            """
                .formatted(
                    UUID.randomUUID(),
                    customer == null ? "" : "\"customerUid\":\"" + customer + "\",",
                    product,
                    quantity,
                    quantity * 1000))
        .expect(201)
        .string("$.data.lines[0].batchNumber");
  }
}
