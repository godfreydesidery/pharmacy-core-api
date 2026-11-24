package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * A till that sends the same sale many times at once (a double tap, a phone retrying on a slow
 * network) gets the one sale back every time: no conflict, no second sale, stock taken once. The
 * chaos run saw 15 of 20 such requests answered 409 before.
 */
class DuplicateSaleTest extends IntegrationTest {

  @Test
  @DisplayName("twenty identical sales at once all answer with the one sale")
  void sameKeyAtOnce() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "DS" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Dup %s","legalName":"Dup %s Ltd","country":"TZ",
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
    Api till = admin.in(tenant, branch);
    String product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"ORS Sachet","genericName":"Oral rehydration salts","dosageForm":"POWDER",
                 "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
                 "sellingPrice":800,"costPrice":300,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"B-1","expiryDate":"%s","quantity":50,"unitCost":300}
            """
                .formatted(product, LocalDate.now().plusYears(2)))
        .expect(201);

    String body =
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":2}],
         "payments":[{"method":"CASH","amount":1600}]}
        """
            .formatted(UUID.randomUUID(), product);
    List<Api.Response> answers =
        IntStream.range(0, 20)
            .mapToObj(i -> CompletableFuture.supplyAsync(() -> till.post("/api/v1/sales", body)))
            .toList()
            .stream()
            .map(CompletableFuture::join)
            .toList();

    assertThat(answers).allSatisfy(a -> assertThat(a.status()).as(a.body()).isIn(200, 201));
    Set<String> sales =
        answers.stream().map(a -> a.string("$.data.uid")).collect(Collectors.toSet());
    assertThat(sales).hasSize(1);
    // A late retry gets the same sale too.
    assertThat(till.post("/api/v1/sales", body).string("$.data.uid"))
        .isEqualTo(sales.iterator().next());
    List<Integer> left =
        till.get("/api/v1/stock")
            .expect(200)
            .json()
            .read("$.data.items[?(@.productUid == '" + product + "')].quantity");
    assertThat(left).containsExactly(48);
  }
}
