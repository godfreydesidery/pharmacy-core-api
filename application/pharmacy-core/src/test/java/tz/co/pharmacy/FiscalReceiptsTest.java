package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalAnswer;
import tz.co.pharmacy.modules.sales.ports.outbound.FiscalReceipts;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * PERF-1: the fiscal receipt is issued after the sale commits, never while the sale's transaction
 * holds the branch's sale number and the stock. A tax authority that is down does not lose the
 * sale, nor the receipt: the sale answers at once with the receipt due, the outbox delivers the
 * {@code sale.completed} event again, and the receipt is issued once the authority answers.
 *
 * <p>Its own context: a fiscal integration is on (a test authority that can be switched off), and
 * undelivered events are delivered again after a second instead of five minutes.
 */
@TestPropertySource(
    properties = {
      "pharmacy.integrations.fiscal.provider=test-authority",
      "pharmacy.events.resubmit-every=PT1S",
      "pharmacy.events.resubmit-older-than=PT1S"
    })
@Import(FiscalReceiptsTest.Authority.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FiscalReceiptsTest extends IntegrationTest {

  static final AtomicBoolean DOWN = new AtomicBoolean();
  static final List<String> ISSUED = new CopyOnWriteArrayList<>();
  static final List<String> REFUSED = new CopyOnWriteArrayList<>();

  /** A tax authority that answers {@code VFD-<sale number>}, or fails while it is down. */
  @TestConfiguration
  static class Authority {
    @Bean
    FiscalReceipts testAuthority() {
      return request -> {
        String saleNumber = request.number();
        if (DOWN.get()) {
          REFUSED.add(saleNumber);
          throw new IllegalStateException("The tax authority does not answer");
        }
        ISSUED.add(saleNumber);
        return FiscalAnswer.issued("VFD-" + saleNumber, null, null);
      };
    }
  }

  @Test
  @DisplayName("a sale is kept when the tax authority is down; its receipt is issued once, later")
  void receiptAfterCommit() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "FR" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Fiscal %s","legalName":"Fiscal %s Ltd","country":"TZ",
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
                {"name":"ORS Sachet","genericName":"Oral rehydration salts","dosageForm":"POWDER",
                 "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
                 "sellingPrice":800,"costPrice":300,"taxRatePercent":18}
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

    // The authority answers: the sale comes back with the receipt due, then gets it.
    Api.Response first = sell(till, product).expect(201);
    String firstUid = first.string("$.data.uid");
    String firstNumber = first.string("$.data.number");
    assertThat((Boolean) first.json().read("$.data.fiscalDue")).isTrue();
    Api.eventually(
        () ->
            ("VFD-" + firstNumber)
                .equals(
                    till.get("/api/v1/sales/" + firstUid)
                        .expect(200)
                        .string("$.data.fiscalReference")));
    assertThat((Boolean) till.get("/api/v1/sales/" + firstUid).json().read("$.data.fiscalDue"))
        .isFalse();

    // The authority is down: the sale is made all the same, with its stock and its number.
    DOWN.set(true);
    Api.Response second = sell(till, product).expect(201);
    String secondUid = second.string("$.data.uid");
    String secondNumber = second.string("$.data.number");
    assertThat(secondNumber).isNotEqualTo(firstNumber);
    Api.eventually(() -> REFUSED.contains(secondNumber));
    Api.Response kept = till.get("/api/v1/sales/" + secondUid).expect(200);
    assertThat((Boolean) kept.json().read("$.data.fiscalDue")).isTrue();
    assertThat((Object) kept.json().read("$.data.fiscalReference")).isNull();
    List<Integer> left =
        till.get("/api/v1/stock")
            .expect(200)
            .json()
            .read("$.data.items[?(@.productUid == '" + product + "')].quantity");
    assertThat(left).containsExactly(48);

    // The authority is back: the outbox delivers the event again and the receipt is issued, once.
    DOWN.set(false);
    Api.eventually(
        () ->
            ("VFD-" + secondNumber)
                .equals(
                    till.get("/api/v1/sales/" + secondUid)
                        .expect(200)
                        .string("$.data.fiscalReference")));
    assertThat(ISSUED).containsOnlyOnce(firstNumber, secondNumber);
  }

  private static Api.Response sell(Api till, String product) {
    return till.post(
        "/api/v1/sales",
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
         "payments":[{"method":"CASH","amount":%s}]}
        """
            .formatted(UUID.randomUUID(), product, new BigDecimal("1000")));
  }
}
