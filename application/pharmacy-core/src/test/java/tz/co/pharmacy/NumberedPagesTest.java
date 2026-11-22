package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Lists asked for by page number ({@code ?page=2&limit=5}) answer one page and the list's total,
 * whatever builds them: an entity list (customers), the product search, a SQL list (stock) and the
 * audit trail. Without {@code page} they still page by cursor.
 */
class NumberedPagesTest extends IntegrationTest {

  private Api admin;
  private Api till;

  @BeforeEach
  void pharmacyWithTwelveOfEverything() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "NP" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Pages %s","legalName":"Pages %s Ltd","country":"TZ",
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
    for (int i = 1; i <= 12; i++) {
      admin
          .post(
              "/api/v1/customers",
              "{\"name\":\"Customer %02d\",\"phone\":\"+2557000000%02d\"}".formatted(i, i))
          .expect(201);
      String product =
          admin
              .post(
                  "/api/v1/products",
                  """
                  {"name":"Product %02d","genericName":"Generic","dosageForm":"TABLET",
                   "schedule":"OTC","sellingPrice":1000,"costPrice":500,"taxRatePercent":0,
                   "storage":"ROOM_TEMPERATURE"}
                  """
                      .formatted(i))
              .expect(201)
              .string("$.data.uid");
      till.post(
              "/api/v1/stock/opening",
              """
              {"productUid":"%s","batchNumber":"B%02d","expiryDate":"%s","quantity":10,"unitCost":500}
              """
                  .formatted(product, i, LocalDate.now().plusYears(1)))
          .expect(201);
    }
  }

  private static void secondOfThree(Response page, int total) {
    assertThat(page.read("$.data.total", Integer.class)).isEqualTo(total);
    assertThat(page.read("$.data.items.length()", Integer.class)).isEqualTo(5);
  }

  @Test
  @DisplayName("each kind of list answers a numbered page with its total")
  void numberedPages() {
    secondOfThree(admin.get("/api/v1/customers?page=2&limit=5").expect(200), 12);
    secondOfThree(admin.get("/api/v1/products?page=2&limit=5").expect(200), 12);
    secondOfThree(till.get("/api/v1/stock?page=2&limit=5").expect(200), 12);
    Response audit = admin.get("/api/v1/audit?page=1&limit=5").expect(200);
    assertThat(audit.read("$.data.total", Integer.class)).isGreaterThan(5);

    // The last page holds the rest; the pages never overlap.
    List<String> names = new ArrayList<>();
    for (int page = 1; page <= 3; page++) {
      names.addAll(
          admin
              .get("/api/v1/products?page=" + page + "&limit=5")
              .expect(200)
              .json()
              .read("$.data.items[*].name"));
    }
    assertThat(names).hasSize(12).doesNotHaveDuplicates();
  }

  @Test
  @DisplayName("short lists page too, and stay whole for pickers without a page number")
  void shortAndRecordLists() {
    for (int i = 1; i <= 7; i++) {
      admin.post("/api/v1/manufacturers", "{\"name\":\"Maker %02d\"}".formatted(i)).expect(201);
    }
    Response page = admin.get("/api/v1/manufacturers?page=2&limit=5").expect(200);
    assertThat(page.read("$.data.total", Integer.class)).isEqualTo(7);
    assertThat(page.read("$.data.items.length()", Integer.class)).isEqualTo(2);
    assertThat(
            admin.get("/api/v1/manufacturers").expect(200).read("$.data.length()", Integer.class))
        .isEqualTo(7);
    // A record list that used to stop at its newest 100 now pages through all of it.
    Response vouchers = admin.get("/api/v1/vouchers?page=1&limit=5").expect(200);
    assertThat(vouchers.read("$.data.total", Integer.class)).isZero();
    Response shifts = till.get("/api/v1/shifts?page=1&limit=5").expect(200);
    assertThat(shifts.read("$.data.total", Integer.class)).isZero();
  }

  @Test
  @DisplayName("without a page number a list still pages by cursor, and page 0 is refused")
  void cursorStillWorks() {
    Response first = admin.get("/api/v1/customers?limit=5").expect(200);
    assertThat(first.string("$.data.nextCursor")).isNotBlank();
    assertThat(admin.get("/api/v1/customers?page=0").expect(422).string("$.reason"))
        .isEqualTo("invalid_page");
  }
}
