package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Exports hold every row the filters match (not only a page), as Excel or CSV, headed in English or
 * Swahili.
 */
class ExportsTest extends IntegrationTest {

  private Api admin;
  private Api till;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "EX" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Export %s","legalName":"Export %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x")).in(tenant, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenant, branch);
  }

  private String product(String name, String schedule) {
    return admin
        .post(
            "/api/v1/products",
            """
            {"name":"%s","genericName":"Generic","dosageForm":"TABLET","schedule":"%s",
             "sellingPrice":1000,"costPrice":500,"taxRatePercent":0,"storage":"ROOM_TEMPERATURE"}
            """
                .formatted(name, schedule))
        .expect(201)
        .string("$.data.uid");
  }

  @Test
  @DisplayName("an export holds every matching row, past the first page, in either language")
  void everyRow() {
    for (int i = 1; i <= 105; i++) {
      product("Product %03d".formatted(i), "OTC");
    }
    String csv = admin.get("/api/v1/products/export?format=csv").expect(200).body();
    String[] lines = csv.split("\r\n");
    assertThat(lines).hasSize(106);
    assertThat(lines[0]).startsWith("﻿\"Code\",\"Name\"");
    assertThat(csv).contains("\"P00105\"", "\"Product 105\"");

    // Filters apply as on screen.
    String one = admin.get("/api/v1/products/export?format=csv&q=Product%20042").expect(200).body();
    assertThat(one.split("\r\n")).hasSize(2);

    String swahili = admin.get("/api/v1/products/export?format=csv&lang=sw").expect(200).body();
    assertThat(swahili).startsWith("﻿\"Msimbo\",\"Jina\"");

    // Excel is the default: a workbook (a zip).
    assertThat(admin.get("/api/v1/products/export").expect(200).body()).startsWith("PK");
    assertThat(admin.get("/api/v1/products/export?format=pdf").expect(422).string("$.reason"))
        .isEqualTo("invalid_format");
  }

  @Test
  @DisplayName("the other lists export too, the controlled register included")
  void otherLists() {
    String morphine = product("Morphine 10mg", "CONTROLLED");
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"MOR-1","expiryDate":"%s","quantity":20,"unitCost":500}
            """
                .formatted(morphine, LocalDate.now().plusDays(30)))
        .expect(201);
    admin
        .post("/api/v1/customers", "{\"name\":\"City Clinic\",\"phone\":\"+255700000001\"}")
        .expect(201);

    assertThat(till.get("/api/v1/stock/export?format=csv").expect(200).body().split("\r\n"))
        .hasSize(2);
    assertThat(till.get("/api/v1/stock/expiring/export?format=csv").expect(200).body())
        .contains("\"MOR-1\"");
    assertThat(till.get("/api/v1/sales/export?format=csv").expect(200).body().split("\r\n"))
        .hasSize(1);
    assertThat(admin.get("/api/v1/customers/export?format=csv").expect(200).body())
        .contains("\"City Clinic\"");
    assertThat(admin.get("/api/v1/suppliers/export?format=csv").expect(200).body())
        .startsWith("﻿\"Code\"");
    assertThat(admin.get("/api/v1/receivables/accounts/export?format=csv").expect(200).body())
        .startsWith("﻿\"Customer\"");
    String register =
        till.get("/api/v1/controlled-drugs/" + morphine + "/register/export?format=csv")
            .expect(200)
            .body();
    assertThat(register.split("\r\n")).hasSize(2);
    assertThat(register).contains("\"MOR-1\"", "\"20\"");
  }
}
