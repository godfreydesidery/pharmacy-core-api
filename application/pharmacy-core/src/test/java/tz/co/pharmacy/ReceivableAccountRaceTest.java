package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * A customer's receivable account is opened by their first entry. The world saw two claim
 * rejections charged to one new patient at once both insert it: the second failed on the unique key
 * and waited for its event's redelivery, leaving ledger 1100 above what customers owe. Now the
 * second waits for the first (the customer's credit lock) and uses the account it opened.
 */
class ReceivableAccountRaceTest extends IntegrationTest {

  @Test
  @DisplayName("ten first entries at once for a new customer open one account and all post")
  void firstEntriesAtOnce() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "RA" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Race %s","legalName":"Race %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String customer =
        admin
            .post("/api/v1/customers", "{\"name\":\"Asha Juma\"}")
            .expect(201)
            .string("$.data.uid");

    List<Api.Response> answers =
        IntStream.range(0, 10)
            .mapToObj(
                i ->
                    CompletableFuture.supplyAsync(
                        () ->
                            admin.post(
                                "/api/v1/receivables/payments",
                                """
                                {"customerUid":"%s","amount":1000,"method":"CASH",
                                 "reference":"RACE-%d"}
                                """
                                    .formatted(customer, i))))
            .toList()
            .stream()
            .map(CompletableFuture::join)
            .toList();

    assertThat(answers).allSatisfy(a -> assertThat(a.status()).as(a.body()).isEqualTo(201));
    assertThat(
            admin
                .get("/api/v1/receivables/totals")
                .expect(200)
                .read("$.data.unallocatedCredit", Double.class))
        .isEqualTo(10_000.0);
  }
}
