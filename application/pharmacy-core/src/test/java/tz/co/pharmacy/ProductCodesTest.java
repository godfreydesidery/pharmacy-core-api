package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Every product has a code, unique in its pharmacy: one left empty is numbered P00001, P00002… past
 * codes already taken; a code typed in is kept; a code another product has is refused; and another
 * pharmacy may use the same codes.
 */
class ProductCodesTest extends IntegrationTest {

  private Api anonymous;
  private Api root;

  @BeforeEach
  void signIn() {
    anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
  }

  private Api pharmacy() {
    String code = "PC" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Codes %s","legalName":"Codes %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    return anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
  }

  private static Response product(Api admin, String name, String sku) {
    return admin.post(
        "/api/v1/products",
        """
        {"name":"%s","genericName":"Generic","dosageForm":"TABLET","schedule":"OTC",
         "sellingPrice":1000,"costPrice":500,"taxRatePercent":0,"storage":"ROOM_TEMPERATURE"%s}
        """
            .formatted(name, sku == null ? "" : ",\"sku\":\"" + sku + "\""));
  }

  @Test
  @DisplayName("an empty code is numbered, a typed one kept, a taken one refused")
  void codes() {
    Api admin = pharmacy();
    assertThat(product(admin, "First", null).expect(201).string("$.data.sku")).isEqualTo("P00001");
    assertThat(product(admin, "Second", null).expect(201).string("$.data.sku")).isEqualTo("P00002");
    // Someone typed the next automatic code by hand: numbering skips past it.
    assertThat(product(admin, "Typed", "P00003").expect(201).string("$.data.sku"))
        .isEqualTo("P00003");
    String fourth = product(admin, "Fourth", null).expect(201).string("$.data.sku");
    assertThat(fourth).isEqualTo("P00004");
    assertThat(product(admin, "Own code", "AMX-500").expect(201).string("$.data.sku"))
        .isEqualTo("AMX-500");
    // Unique in the pharmacy, whatever the capitals.
    assertThat(product(admin, "Clash", "amx-500").expect(409).string("$.reason"))
        .isEqualTo("duplicate_sku");

    // A change that leaves the code empty keeps it.
    String uid = product(admin, "Kept", null).expect(201).string("$.data.uid");
    Response before = admin.get("/api/v1/products/" + uid).expect(200);
    String kept = before.string("$.data.sku");
    String version = before.string("$.data.version");
    Response changed =
        admin
            .put(
                "/api/v1/products/" + uid,
                """
                {"name":"Kept renamed","genericName":"Generic","dosageForm":"TABLET","schedule":"OTC",
                 "sellingPrice":1000,"costPrice":500,"taxRatePercent":0,"storage":"ROOM_TEMPERATURE",
                 "active":true,"version":%s}
                """
                    .formatted(version))
            .expect(200);
    assertThat(changed.string("$.data.sku")).isEqualTo(kept);
  }

  @Test
  @DisplayName("each pharmacy numbers its own codes")
  void perPharmacy() {
    Api one = pharmacy();
    Api two = pharmacy();
    assertThat(product(one, "Here", null).expect(201).string("$.data.sku")).isEqualTo("P00001");
    assertThat(product(two, "There", null).expect(201).string("$.data.sku")).isEqualTo("P00001");
    product(two, "Same typed", "AMX-500").expect(201);
    product(one, "Same typed", "AMX-500").expect(201);
  }
}
