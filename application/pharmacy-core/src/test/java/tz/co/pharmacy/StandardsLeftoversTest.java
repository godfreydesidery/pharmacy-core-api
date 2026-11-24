package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Batch 10 standards (adversarial review STD-4, STD-14, STD-15, STD-16, STD-19), over HTTP:
 *
 * <ul>
 *   <li>a branch's invoice numbers are one series: a new invoice prefix carries on counting, and
 *       sales V21 starts each series after the numbers already used;
 *   <li>a problem's type is {@code /problems/<reason>}, served by the API itself; a 401 names its
 *       scheme in {@code WWW-Authenticate};
 *   <li>a 201 names what it created in {@code Location}; every answer carries {@code
 *       X-Api-Version}, the OpenAPI document's version, which /actuator/info shows with the build;
 *   <li>phones are kept in E.164 with the pharmacy's country code, and partners V10 normalised the
 *       ones kept before in every pharmacy.
 * </ul>
 */
class StandardsLeftoversTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  @Value("${pharmacy.api.version}")
  String apiVersion;

  /** A new pharmacy in {@code country}: its admin scoped to it, and to its head office. */
  private record Pharmacy(String uid, Api admin, Api till) {}

  private Pharmacy pharmacy(String country) {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "SL" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String uid =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Leftovers %s","legalName":"Leftovers %s Ltd","country":"%s",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, country, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(uid, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    return new Pharmacy(uid, admin, admin.in(uid, branch));
  }

  // ---------------------------------------------------------------------- STD-4 invoice series

  @Test
  @DisplayName("a new invoice prefix carries on the branch's numbers instead of starting at 1")
  void invoicePrefixKeepsTheSeries() {
    Pharmacy p = pharmacy("TZ");
    String product = stockedProduct(p);

    assertThat(sell(p, product)).isEqualTo("INV-HQ-000001");
    p.admin().put("/api/v1/settings", "{\"invoicePrefix\":\"RCP\"}").expect(200);
    assertThat(sell(p, product)).isEqualTo("RCP-HQ-000002");
    p.admin().put("/api/v1/settings", "{\"invoicePrefix\":\"INV\"}").expect(200);
    assertThat(sell(p, product)).isEqualTo("INV-HQ-000003");
  }

  @Test
  @DisplayName("sales V21 starts each branch's series after the highest number its sales carry")
  void seriesBackfill() {
    Pharmacy p = pharmacy("TZ");
    String product = stockedProduct(p);
    sell(p, product);
    String sale = p.till().get("/api/v1/sales?limit=1").expect(200).string("$.data.items[0].uid");
    // As before the fix: a sale numbered under an earlier prefix, and no series of the branch.
    asOwner(p.uid(), "update sales.sales set number = 'OLD-HQ-000041' where uid = ?::uuid", sale);
    asOwner(
        p.uid(),
        "delete from platform.document_sequences"
            + " where tenant_uid = ?::uuid and sequence_key = 'sale:HQ'",
        p.uid());

    runMigration("db/migration/sales/V21__sale_number_series.sql");

    assertThat(sell(p, product)).isEqualTo("INV-HQ-000042");
  }

  // ---------------------------------------------------- STD-14, STD-15 problem types and 401s

  @Test
  @DisplayName("a problem's type is /problems/<reason>, which the API serves; a 401 names Bearer")
  void problemTypesAndChallenges() {
    Response none = api().get("/api/v1/products").expect(401);
    assertThat(none.string("$.reason")).isEqualTo("unauthenticated");
    assertThat(none.string("$.type")).isEqualTo("/problems/unauthenticated");
    assertThat(none.header("WWW-Authenticate")).isEqualTo("Bearer realm=\"pharmacy-core\"");

    Response stale =
        api().get("/api/v1/products", "Authorization", "Bearer no-such-session").expect(401);
    assertThat(stale.string("$.reason")).isEqualTo("session_expired");
    assertThat(stale.header("WWW-Authenticate"))
        .isEqualTo("Bearer realm=\"pharmacy-core\", error=\"invalid_token\"");

    // A refusal answered by a handler (not the edge) carries the same type.
    Pharmacy p = pharmacy("TZ");
    Response missing = p.admin().get("/api/v1/customers/" + UUID.randomUUID()).expect(404);
    assertThat(missing.string("$.type")).isEqualTo("/problems/" + missing.string("$.reason"));

    Response page = api().get("/problems/unauthenticated").expect(200);
    assertThat(page.header("Content-Type")).startsWith("text/plain");
    assertThat(page.body()).contains("unauthenticated").contains("reason");
    api().get("/problems/Not-A-Reason").expect(404);
  }

  // ------------------------------------------------------------ STD-16 Location and versions

  @Test
  @DisplayName("a 201 names its resource in Location, and the resource is there")
  void createdNamesItsLocation() {
    Pharmacy p = pharmacy("TZ");
    Response created = p.admin().post("/api/v1/customers", "{\"name\":\"Asha Juma\"}").expect(201);
    String uid = created.string("$.data.uid");
    assertThat(created.header("Location")).isEqualTo("/api/v1/customers/" + uid);
    assertThat(p.admin().get(created.header("Location")).expect(200).string("$.data.uid"))
        .isEqualTo(uid);

    Response product =
        p.admin()
            .post(
                "/api/v1/products",
                """
                {"name":"Zinc 20mg","genericName":"Zinc sulfate","dosageForm":"TABLET",
                 "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH","sellingPrice":500,"costPrice":200,"taxRatePercent":0}
                """)
            .expect(201);
    assertThat(product.header("Location"))
        .isEqualTo("/api/v1/products/" + product.string("$.data.uid"));
  }

  @Test
  @DisplayName("every answer carries X-Api-Version: the OpenAPI version, shown in /actuator/info")
  void versionIsExposed() {
    assertThat(apiVersion).matches("\\d+\\.\\d+\\.\\d+");
    Response answer = api().get("/api/v1/products");
    assertThat(answer.header("X-Api-Version")).isEqualTo(apiVersion);
    assertThat(api().get("/v3/api-docs").expect(200).string("$.info.version"))
        .isEqualTo(apiVersion);
    Response info = api().get("/actuator/info").expect(200);
    assertThat(info.string("$.api.version")).isEqualTo(apiVersion);
    assertThat(info.string("$.build.version")).isNotBlank();
    assertThat(info.body()).doesNotContain("\"java\"").doesNotContain("\"os\"");
  }

  // ------------------------------------------------------------------------ STD-19 phones

  @Test
  @DisplayName("phones are kept in E.164 with the pharmacy's country code, and found as typed")
  void phonesInE164() {
    Pharmacy tz = pharmacy("TZ");
    String customer =
        tz.admin()
            .post("/api/v1/customers", "{\"name\":\"Neema\",\"phone\":\"0754 000 111\"}")
            .expect(201)
            .string("$.data.uid");
    assertThat(tz.admin().get("/api/v1/customers/" + customer).string("$.data.phone"))
        .isEqualTo("+255754000111");
    assertThat(
            tz.admin()
                .get("/api/v1/customers?q=0754%20000")
                .expect(200)
                .json()
                .read("$.data.items[*].uid", java.util.List.class))
        .contains(customer);
    Response junk =
        tz.admin().post("/api/v1/customers", "{\"name\":\"X\",\"phone\":\"call the shop\"}");
    assertThat(junk.status()).isEqualTo(422);
    assertThat(junk.string("$.reason")).isEqualTo("invalid_phone");

    assertThat(
            tz.admin()
                .post(
                    "/api/v1/suppliers",
                    """
                    {"name":"Wholesale","phone":"255 22 211 0000","paymentTerms":"COD"}
                    """)
                .expect(201)
                .string("$.data.phone"))
        .isEqualTo("+255222110000");
    String head = tz.admin().get("/api/v1/auth/me").string("$.data.memberships[0].branches[0].uid");
    assertThat(
            tz.admin()
                .put(
                    "/api/v1/branches/" + head,
                    "{\"code\":\"HQ\",\"name\":\"Head Office\",\"phone\":\"0655000111\"}")
                .expect(200)
                .string("$.data.phone"))
        .isEqualTo("+255655000111");

    // A Kenyan pharmacy's local number takes Kenya's code.
    Pharmacy ke = pharmacy("KE");
    assertThat(
            ke.admin()
                .post("/api/v1/customers", "{\"name\":\"Wanjiku\",\"phone\":\"0712 345 678\"}")
                .expect(201)
                .string("$.data.phone"))
        .isEqualTo("+254712345678");
  }

  @Test
  @DisplayName(
      "partners V10 normalises every pharmacy's phones and leaves unreadable ones as they were")
  void phoneBackfill() {
    Pharmacy tz = pharmacy("TZ");
    Pharmacy ke = pharmacy("KE");
    String asha = customer(tz, "Asha");
    String legacy = customer(tz, "Legacy");
    String wanjiku = customer(ke, "Wanjiku");
    // Phones as they were kept before the API normalised them.
    asOwner(
        tz.uid(), "update partners.customers set phone = '0712 345 678' where uid = ?::uuid", asha);
    asOwner(
        tz.uid(),
        "update partners.customers set phone = 'Ask at the counter' where uid = ?::uuid",
        legacy);
    asOwner(
        ke.uid(),
        "update partners.customers set phone = '0712-345-678' where uid = ?::uuid",
        wanjiku);

    runMigration("db/migration/partners/V10__phones_e164.sql");

    assertThat(phone(tz, asha)).isEqualTo("+255712345678");
    assertThat(phone(ke, wanjiku)).isEqualTo("+254712345678");
    assertThat(phone(tz, legacy)).isEqualTo("Ask at the counter");

    // The unreadable one stays while it is sent back unchanged; a new one must be a number.
    tz.admin()
        .put(
            "/api/v1/customers/" + legacy,
            "{\"name\":\"Legacy Renamed\",\"phone\":\"Ask at the counter\"}")
        .expect(200);
    assertThat(phone(tz, legacy)).isEqualTo("Ask at the counter");
    Response changed =
        tz.admin()
            .put("/api/v1/customers/" + legacy, "{\"name\":\"Legacy\",\"phone\":\"Ask Juma\"}");
    assertThat(changed.status()).isEqualTo(422);
    assertThat(changed.string("$.reason")).isEqualTo("invalid_phone");
  }

  // ------------------------------------------------------------------------------ helpers

  private static String customer(Pharmacy p, String name) {
    return p.admin()
        .post("/api/v1/customers", "{\"name\":\"%s\"}".formatted(name))
        .expect(201)
        .string("$.data.uid");
  }

  private static String phone(Pharmacy p, String customer) {
    return p.admin().get("/api/v1/customers/" + customer).expect(200).string("$.data.phone");
  }

  private static String stockedProduct(Pharmacy p) {
    String product =
        p.admin()
            .post(
                "/api/v1/products",
                """
                {"name":"ORS Sachet","genericName":"Oral rehydration salts","dosageForm":"POWDER",
                 "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
                 "sellingPrice":800,"costPrice":300,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
    p.till()
        .post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"B-1","expiryDate":"%s","quantity":50,"unitCost":300}
            """
                .formatted(product, LocalDate.now().plusYears(2)))
        .expect(201);
    return product;
  }

  /** One cash sale of one unit; its number. */
  private static String sell(Pharmacy p, String product) {
    return p.till()
        .post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":1}],
             "payments":[{"method":"CASH","amount":800}]}
            """
                .formatted(UUID.randomUUID(), product))
        .expect(201)
        .string("$.data.number");
  }

  /** A statement as the schema owner, in one pharmacy (row-level security is forced on it too). */
  private void asOwner(String tenantUid, String statement, Object... args) {
    try (Connection c = DriverManager.getConnection(url, owner, ownerPassword)) {
      c.setAutoCommit(false);
      try (PreparedStatement scope =
          c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        scope.setString(1, tenantUid);
        try (ResultSet ignored = scope.executeQuery()) {
          // scoped
        }
      }
      try (PreparedStatement s = c.prepareStatement(statement)) {
        for (int i = 0; i < args.length; i++) {
          s.setObject(i + 1, args[i]);
        }
        s.execute();
      }
      c.commit();
    } catch (SQLException failed) {
      throw new IllegalStateException(failed);
    }
  }

  /** A migration's script again, as Flyway runs it: the owner, in no pharmacy, one transaction. */
  private void runMigration(String resource) {
    String script;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failed) {
      throw new UncheckedIOException(failed);
    }
    try (Connection c = DriverManager.getConnection(url, owner, ownerPassword)) {
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute(script);
      }
      c.commit();
    } catch (SQLException failed) {
      throw new IllegalStateException(failed);
    }
  }
}
