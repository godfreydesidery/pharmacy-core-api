package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Staff;

/**
 * The till and branch holes the adversarial review of 23 Nov 2026 found, closed: a voucher needs
 * its own permission and is spent in the base currency only, a foreign tender is valued at the
 * recorded rate, a non-cash tender names the method it was taken with, and a user limited to one
 * branch reaches neither another branch's sales nor their files.
 */
class TillAndBranchHardeningTest extends IntegrationTest {

  private static final double USD = 2_650.0;
  private static final byte[] PDF =
      "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

  private Api anonymous;
  private Api admin;
  private Api hq;
  private Api second;
  private String tenant;
  private String hqUid;
  private String secondUid;
  private String product;

  @BeforeEach
  void pharmacy() {
    anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "HB" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Hardened %s","legalName":"Hardened %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    hqUid =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    secondUid =
        admin
            .post("/api/v1/branches", "{\"code\":\"B2\",\"name\":\"Arusha\"}")
            .expect(201)
            .string("$.data.uid");
    hq = admin.in(tenant, hqUid);
    second = admin.in(tenant, secondUid);
    admin.put("/api/v1/settings", "{\"acceptedCurrencies\":[\"TZS\",\"USD\"]}").expect(200);
    admin
        .post(
            "/api/v1/fx/rates",
            "{\"base\":\"USD\",\"quote\":\"TZS\",\"rate\":%s,\"effectiveOn\":\"%s\"}"
                .formatted(USD, LocalDate.now()))
        .expect(201);
    product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Paracetamol 500mg","genericName":"Paracetamol","dosageForm":"TABLET",
                 "schedule":"OTC","sellingPrice":10000,"costPrice":6000,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
    for (Api branch : List.of(hq, second)) {
      branch
          .post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"P-1","expiryDate":"%s","quantity":100,"unitCost":6000}
              """
                  .formatted(product, LocalDate.now().plusYears(2)))
          .expect(201);
    }
  }

  @Test
  @DisplayName("issuing a voucher needs sales.voucher: a cashier cannot create money")
  void voucherNeedsItsOwnPermission() {
    Staff.Login cashier = Staff.login(admin, anonymous, "Cashier", "CASHIER", "Cashier#2026x");
    Api till = anonymous.as(anonymous.signIn(cashier.signIn(), "Cashier#2026x")).in(tenant, hqUid);
    Response refused = till.post("/api/v1/vouchers", "{\"value\":500000,\"paidBy\":\"CASH\"}");
    assertThat(refused.expect(403).string("$.reason")).isEqualTo("forbidden");

    hq.post("/api/v1/vouchers", "{\"value\":5000,\"paidBy\":\"CASH\"}").expect(201);
  }

  @Test
  @DisplayName("a voucher is spent in the base currency only")
  void voucherInBaseCurrencyOnly() {
    String voucher =
        hq.post("/api/v1/vouchers", "{\"value\":50,\"paidBy\":\"CASH\"}")
            .expect(201)
            .string("$.data.code");
    Response foreign =
        sell(
            hq,
            "{\"method\":\"VOUCHER\",\"amount\":10,\"currency\":\"USD\",\"reference\":\"%s\"}"
                .formatted(voucher));
    assertThat(foreign.expect(422).string("$.reason")).isEqualTo("base_currency_only");
  }

  @Test
  @DisplayName("a foreign tender is valued at the recorded rate; the cashier cannot set another")
  void recordedRateOnly() {
    Response invented =
        sell(
            hq, "{\"method\":\"CASH\",\"amount\":100,\"currency\":\"USD\",\"exchangeRate\":10000}");
    assertThat(invented.expect(422).string("$.reason")).isEqualTo("exchange_rate_mismatch");

    Response fair =
        sell(
            hq,
            "{\"method\":\"CASH\",\"amount\":4,\"currency\":\"USD\",\"exchangeRate\":%s}"
                .formatted(USD));
    assertThat(fair.expect(201).read("$.data.changeDue", Double.class)).isEqualTo(4 * USD - 10_000);
  }

  @Test
  @DisplayName("mobile money names its method when the pharmacy has several; a single one is used")
  void nonCashNamesItsMethod() {
    Response unnamed = sell(hq, "{\"method\":\"MOBILE_MONEY\",\"amount\":10000}");
    assertThat(unnamed.expect(422).string("$.reason")).isEqualTo("payment_declined");
    assertThat(unnamed.string("$.detail")).contains("MPESA");

    sell(hq, "{\"method\":\"MOBILE_MONEY\",\"channel\":\"MOBILE_OTHER\",\"amount\":10000}")
        .expect(201);
    // The pharmacy has one card method, so an unnamed card payment is booked through it.
    sell(hq, "{\"method\":\"CARD\",\"amount\":10000}").expect(201);
    // A channel of another kind cannot book the payment.
    Response wrongKind =
        sell(hq, "{\"method\":\"CARD\",\"channel\":\"MOBILE_OTHER\",\"amount\":10000}");
    assertThat(wrongKind.expect(422).string("$.reason")).isEqualTo("payment_declined");
  }

  @Test
  @DisplayName("a user limited to one branch reaches neither another branch's sales nor its files")
  void branchLimitedUser() {
    String arushaSale =
        sell(second, "{\"method\":\"CASH\",\"amount\":10000}").expect(201).string("$.data.uid");
    String arushaFile =
        second
            .upload(
                "/api/v1/files?ownerType=SALE&ownerUid=" + arushaSale + "&purpose=PRESCRIPTION",
                "rx.pdf",
                "application/pdf",
                PDF)
            .expect(201)
            .string("$.data.uid");
    String hqSale =
        sell(hq, "{\"method\":\"CASH\",\"amount\":10000}").expect(201).string("$.data.uid");
    String hqFile =
        hq.upload(
                "/api/v1/files?ownerType=SALE&ownerUid=" + hqSale + "&purpose=PRESCRIPTION",
                "rx.pdf",
                "application/pdf",
                PDF)
            .expect(201)
            .string("$.data.uid");

    Api cashier = branchCashier();
    assertThat(cashier.get("/api/v1/sales/" + arushaSale).expect(404).string("$.reason"))
        .isEqualTo("sale_not_found");
    cashier.get("/api/v1/files?ownerType=SALE&ownerUid=" + arushaSale).expect(404);
    cashier.get("/api/v1/files/" + arushaFile + "/url").expect(404);
    cashier.get("/api/v1/files?ownerType=SALE").expect(422);

    // In its own branch the cashier sees the sale and its prescription, but cannot delete it.
    cashier.get("/api/v1/sales/" + hqSale).expect(200);
    cashier.get("/api/v1/files/" + hqFile + "/url").expect(200);
    assertThat(cashier.delete("/api/v1/files/" + hqFile).expect(403).string("$.reason"))
        .isEqualTo("forbidden");
    hq.delete("/api/v1/files/" + hqFile).expect(200);
  }

  /** A cashier whose membership covers the head office only. */
  private Api branchCashier() {
    List<String> roles =
        admin.get("/api/v1/roles").expect(200).json().read("$.data[?(@.code == 'CASHIER')].uid");
    Response created =
        admin
            .post(
                "/api/v1/users/managed",
                """
                {"username":"hq.%s","name":"HQ Cashier","roleUid":"%s","allBranches":false,
                 "branchUids":["%s"]}
                """
                    .formatted(
                        UUID.randomUUID().toString().substring(0, 6), roles.getFirst(), hqUid))
            .expect(201);
    String signIn = created.string("$.data.signIn");
    String temporary = created.string("$.data.temporaryPassword");
    Api first = anonymous.as(anonymous.signIn(signIn, temporary));
    first
        .post(
            "/api/v1/auth/change-password",
            "{\"currentPassword\":\"%s\",\"newPassword\":\"Cashier#2026x\"}".formatted(temporary))
        .expect(200);
    return anonymous.as(anonymous.signIn(signIn, "Cashier#2026x")).in(tenant, hqUid);
  }

  private Response sell(Api till, String payment) {
    return till.post(
        "/api/v1/sales",
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],"payments":[%s]}
        """
            .formatted(UUID.randomUUID(), product, payment));
  }
}
