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

/** Registered payment methods, push requests, typed references, bank transfers and vouchers. */
class PaymentsTest extends IntegrationTest {

  @Test
  @DisplayName(
      "the till takes push, typed-reference, bank and voucher payments through the registry")
  void payments() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "PY" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Pay %s","legalName":"Pay %s Ltd","country":"TZ",
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
    Api till = admin.in(tenant, branch);
    String product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Vitamin C","genericName":"Ascorbic acid","dosageForm":"TABLET",
                 "schedule":"OTC","sellingPrice":10000,"costPrice":6000}
                """)
            .expect(201)
            .string("$.data.uid");
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"V-1","expiryDate":"%s","quantity":100,"unitCost":6000}
            """
                .formatted(product, LocalDate.now().plusYears(1)))
        .expect(201);

    List<String> offered =
        till.get("/api/v1/pos/payment-methods").expect(200).json().read("$.data[*].code");
    assertThat(offered)
        .contains("CASH", "MPESA", "BANK_TRANSFER", "VOUCHER")
        .doesNotContain("SIMULATOR");

    // The root administrator turns the simulator on; the pharmacy enables it for its till.
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

    // Push a request to the phone; it is confirmed and pays one sale only.
    String intent =
        till.post(
                "/api/v1/pos/payment-requests",
                """
                {"methodCode":"SIMULATOR","amount":10000,"currency":"TZS","phone":"+255754000111"}
                """)
            .expect(201)
            .string("$.data.uid");
    assertThat(
            till.get("/api/v1/pos/payment-requests/" + intent).expect(200).string("$.data.status"))
        .isEqualTo("CONFIRMED");
    Response pushed =
        sell(till, product, payment("MOBILE_MONEY", 10000, "SIMULATOR", intent)).expect(201);
    assertThat(pushed.string("$.data.payments[0].reference")).startsWith("SIM-");
    assertThat(pushed.string("$.data.payments[0].channel")).isEqualTo("SIMULATOR");
    assertThat(
            sell(till, product, payment("MOBILE_MONEY", 10000, "SIMULATOR", intent))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("payment_declined");

    // M-Pesa is not connected: no push, but its confirmation code typed by hand is accepted.
    assertThat(
            till.post(
                    "/api/v1/pos/payment-requests",
                    "{\"methodCode\":\"MPESA\",\"amount\":10000,\"currency\":\"TZS\",\"phone\":\"0754000111\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("adapter_unavailable");
    assertThat(
            sell(till, product, payment("MOBILE_MONEY", 10000, "MPESA", null))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("payment_declined");
    sell(till, product, payment("MOBILE_MONEY", 10000, "MPESA", "QKX12ABC9")).expect(201);

    // A method the pharmacy turned off is declined.
    admin.put("/api/v1/payment-methods", "{\"methodCode\":\"CARD\",\"enabled\":false}").expect(200);
    assertThat(
            sell(till, product, payment("CARD", 10000, "CARD", null))
                .expect(422)
                .string("$.reason"))
        .isEqualTo("payment_declined");

    // Bank transfer by reference.
    sell(till, product, payment("BANK_TRANSFER", 10000, "BANK_TRANSFER", "CRDB-TT-55")).expect(201);

    // A voucher: sold for cash, half the sale paid with it, then spent.
    String voucher =
        till.post(
                "/api/v1/vouchers", "{\"value\":5000,\"paidBy\":\"CASH\",\"holderName\":\"Asha\"}")
            .expect(201)
            .string("$.data.code");
    sell(
            till,
            product,
            payment("VOUCHER", 5000, "VOUCHER", voucher),
            "{\"method\":\"CASH\",\"amount\":5000}")
        .expect(201);
    List<String> statuses =
        admin.get("/api/v1/vouchers").expect(200).json().read("$.data.items[*].status");
    assertThat(statuses).containsExactly("SPENT");
    Response spent =
        sell(
                till,
                product,
                payment("VOUCHER", 1000, "VOUCHER", voucher),
                "{\"method\":\"CASH\",\"amount\":9000}")
            .expect(422);
    assertThat(spent.string("$.reason")).isEqualTo("voucher_not_active");
    assertThat(spent.string("$.detail")).endsWith("has been used up. It can no longer be spent.");
    Api.eventually(
        () -> admin.get("/api/v1/journal?limit=100").expect(200).body().contains("\"2300\""));
  }

  private static String payment(String method, int amount, String channel, String reference) {
    return "{\"method\":\"%s\",\"amount\":%d,\"channel\":\"%s\"%s}"
        .formatted(
            method,
            amount,
            channel,
            reference == null ? "" : ",\"reference\":\"" + reference + "\"");
  }

  private static Response sell(Api till, String product, String... payments) {
    return till.post(
        "/api/v1/sales",
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],"payments":[%s]}
        """
            .formatted(UUID.randomUUID(), product, String.join(",", payments)));
  }
}
