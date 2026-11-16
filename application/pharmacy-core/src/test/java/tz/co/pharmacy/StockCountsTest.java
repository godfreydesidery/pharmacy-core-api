package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/** Stock-takes: a count sheet of the branch, recorded counts, and posting the differences. */
class StockCountsTest extends IntegrationTest {

  @Test
  @DisplayName("a stock-take lists every batch, values the differences and posts corrections once")
  void stockTake() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SC" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Count %s","legalName":"Count %s Ltd","country":"TZ",
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

    String amox =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Amoxicillin 500mg","genericName":"Amoxicillin","dosageForm":"CAPSULE",
                 "schedule":"OTC","sellingPrice":300,"costPrice":120}
                """)
            .expect(201)
            .string("$.data.uid");
    hq.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"AM-1","expiryDate":"%s","quantity":50,"unitCost":120}
            """
                .formatted(amox, LocalDate.now().plusMonths(12)))
        .expect(201);

    Response started = hq.post("/api/v1/stock-counts", "{\"note\":\"Month end\"}").expect(201);
    String count = started.string("$.data.uid");
    assertThat(started.string("$.data.number")).startsWith("CNT");
    assertThat(started.read("$.data.lines", Integer.class)).isEqualTo(1);
    assertThat(started.read("$.data.items[0].expected", Integer.class)).isEqualTo(50);
    String line = started.string("$.data.items[0].uid");

    // A negative count is refused; 46 counted means 4 short, 480 at cost.
    hq.put(
            "/api/v1/stock-counts/" + count + "/counts",
            "[{\"lineUid\":\"%s\",\"counted\":-1}]".formatted(line))
        .expect(422);
    Response recorded =
        hq.put(
                "/api/v1/stock-counts/" + count + "/counts",
                "[{\"lineUid\":\"%s\",\"counted\":46}]".formatted(line))
            .expect(200);
    assertThat(recorded.read("$.data.items[0].variance", Integer.class)).isEqualTo(-4);
    assertThat(recorded.read("$.data.varianceValue", Double.class)).isEqualTo(-480.0);

    Response posted = hq.post("/api/v1/stock-counts/" + count + "/post", "{}").expect(200);
    assertThat(posted.string("$.data.status")).isEqualTo("POSTED");
    assertThat(
            hq.get("/api/v1/stock/" + amox + "/batches")
                .expect(200)
                .read("$.data[0].quantity", Integer.class))
        .isEqualTo(46);

    // The shortage reaches the books at cost: Dr 5100 stock losses 480 / Cr 1200 inventory 480
    // (opening stock is not journalled; the opening-balances wizard covers that).
    Api.eventually(() -> balance(admin, "5100") != 0);
    assertThat(balance(admin, "5100")).isEqualTo(480.0);
    assertThat(balance(admin, "1200")).isEqualTo(-480.0);

    // Posting twice, or recording on a posted count, is refused.
    assertThat(
            hq.post("/api/v1/stock-counts/" + count + "/post", "{}").expect(422).string("$.reason"))
        .isEqualTo("count_posted");
    assertThat(hq.get("/api/v1/stock-counts").expect(200).string("$.data[0].status"))
        .isEqualTo("POSTED");
  }

  private static double balance(Api admin, String code) {
    java.util.List<?> balances =
        admin
            .get("/api/v1/accounts")
            .expect(200)
            .json()
            .read("$.data[?(@.code == '" + code + "')].balance", java.util.List.class);
    return balances.isEmpty() ? 0 : ((Number) balances.get(0)).doubleValue();
  }
}
