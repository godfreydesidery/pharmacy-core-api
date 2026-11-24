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

/** Controlled drugs: a full prescription at the till, and the register with running balances. */
class ControlledDrugsTest extends IntegrationTest {

  @Test
  @DisplayName("a controlled sale needs a full prescription and lands in the register")
  void register() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "CD" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Controlled %s","legalName":"Controlled %s Ltd","country":"TZ",
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

    String morphine =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Morphine 10mg","genericName":"Morphine sulfate","dosageForm":"TABLET",
                 "strength":"10 mg","schedule":"CONTROLLED","sellingPrice":1500,"costPrice":900}
                """)
            .expect(201)
            .string("$.data.uid");
    String paracetamol =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Paracetamol","genericName":"Paracetamol","dosageForm":"TABLET",
                 "schedule":"OTC","sellingPrice":100,"costPrice":50}
                """)
            .expect(201)
            .string("$.data.uid");
    hq.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"MOR-1","expiryDate":"%s","quantity":20,"unitCost":900}
            """
                .formatted(morphine, LocalDate.now().plusYears(1)))
        .expect(201);

    String sale =
        """
        {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":2}],
         "payments":[{"method":"CASH","amount":3000}],"prescription":%s}
        """;
    Response partial =
        hq.post(
            "/api/v1/sales",
            sale.formatted(UUID.randomUUID(), morphine, "{\"prescriberName\":\"Dr. Mushi\"}"));
    assertThat(partial.expect(422).string("$.reason"))
        .isEqualTo("controlled_prescription_incomplete");
    String number =
        hq.post(
                "/api/v1/sales",
                sale.formatted(
                    UUID.randomUUID(),
                    morphine,
                    """
                    {"prescriberName":"Dr. Mushi","prescriberRegistration":"MCT-1180",
                     "reference":"KCMC-77","patientName":"Baraka Moshi",
                     "prescriptionDate":"%s"}
                    """
                        .formatted(LocalDate.now().minusDays(1))))
            .expect(201)
            .string("$.data.number");

    // STD-7: the register's products come a page at a time.
    Response products = hq.get("/api/v1/controlled-drugs").expect(200);
    assertThat(products.read("$.data.items[0].name", String.class)).isEqualTo("Morphine 10mg");
    assertThat(products.read("$.data.items[0].onHand", Integer.class)).isEqualTo(18);

    Response register = hq.get("/api/v1/controlled-drugs/" + morphine + "/register").expect(200);
    List<String> types = register.json().read("$.data.items[*].type");
    assertThat(types).containsExactlyInAnyOrder("OPENING", "SALE");
    List<Object> sold =
        register.json().read("$.data.items[?(@.reference == '%s')]".formatted(number));
    assertThat(sold).hasSize(1);
    assertThat(register.read("$.data.items[?(@.type == 'SALE')].patientName", List.class))
        .containsExactly("Baraka Moshi");
    assertThat(register.read("$.data.items[?(@.type == 'SALE')].balance", List.class))
        .containsExactly(18);
    // The pharmacist who dispensed it, and the prescription's date, are in the register (STD-7).
    assertThat(register.read("$.data.items[?(@.type == 'SALE')].dispensedBy", List.class))
        .containsExactly("Admin");
    assertThat(register.read("$.data.items[?(@.type == 'SALE')].prescriptionDate", List.class))
        .containsExactly(LocalDate.now().minusDays(1).toString());

    assertThat(
            hq.get("/api/v1/controlled-drugs/" + paracetamol + "/register")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("not_controlled");
  }
}
