package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.TestPropertySource;
import tz.co.pharmacy.modules.catalogue.domain.Gtin;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.Api.Response;
import tz.co.pharmacy.support.IntegrationTest;

/**
 * Batch 9 standards (adversarial review STD-9, STD-10, STD-18), over HTTP:
 *
 * <ul>
 *   <li>the OpenAPI document carries each route's access rule ({@code x-scope}, {@code
 *       x-permission}…), its headers and its RFC 9457 problems;
 *   <li>an edited resource answers with {@code ETag: "<version>"}, and a change with a stale {@code
 *       If-Match} is 412 precondition_failed; without If-Match it goes ahead unless the server
 *       requires it (428 precondition_required);
 *   <li>a barcode must be a GS1 GTIN with its check digit (422 invalid_gtin), while a product
 *       holding a wrong one from before stays readable and editable;
 *   <li>batch numbers are one per product whatever their case.
 * </ul>
 */
class StandardsTest extends IntegrationTest {

  @Value("${spring.datasource.url}")
  String url;

  @Value("${pharmacy.database.owner-username}")
  String owner;

  @Value("${pharmacy.database.owner-password}")
  String ownerPassword;

  private Api admin;
  private Api till;
  private String tenantUid;

  @BeforeEach
  void pharmacy() {
    Api anonymous = api();
    Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = "ST" + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    tenantUid =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Standards %s","legalName":"Standards %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenantUid, null);
    String branch =
        admin.get("/api/v1/auth/me").expect(200).string("$.data.memberships[0].branches[0].uid");
    till = admin.in(tenantUid, branch);
  }

  // ------------------------------------------------------------------------------ STD-9 OpenAPI

  @Test
  @DisplayName("the OpenAPI document carries each route's scope, permission, headers and problems")
  void openApiCarriesTheAccessRules() {
    Response docs = api().get("/v3/api-docs").expect(200);
    assertThat(docs.string("$.openapi")).startsWith("3.1");

    String put = "$.paths['/api/v1/products/{uid}'].put";
    assertThat(docs.string(put + "['x-scope']")).isEqualTo("TENANT");
    assertThat(docs.string(put + "['x-permission']")).isEqualTo("catalogue.manage");
    assertThat(docs.read(put + "['x-if-match']", Boolean.class)).isTrue();
    assertThat(docs.string(put + ".description")).contains("permission `catalogue.manage`");
    List<String> refs = docs.json().read(put + ".parameters[*]['$ref']");
    assertThat(refs)
        .contains(
            "#/components/parameters/tenant",
            "#/components/parameters/ifMatch",
            "#/components/parameters/approval");
    assertThat(docs.json().read(put + ".responses['412']", Map.class)).isNotNull();
    assertThat(docs.json().read(put + ".responses['428']", Map.class)).isNotNull();
    assertThat(docs.string(put + ".responses.default['$ref']"))
        .isEqualTo("#/components/responses/Problem");

    // A branch route names both headers; an idempotent one takes Idempotency-Key.
    String sale = "$.paths['/api/v1/stock/opening'].post";
    assertThat(docs.string(sale + "['x-scope']")).isEqualTo("BRANCH");
    assertThat(docs.string(sale + "['x-permission']")).isEqualTo("inventory.adjust");
    assertThat(docs.read(sale + "['x-idempotent']", Boolean.class)).isTrue();
    assertThat(docs.json().read(sale + ".parameters[*]['$ref']", List.class))
        .contains(
            "#/components/parameters/branchRequired", "#/components/parameters/idempotencyKey");

    // A public route needs no session; a read of a list names no If-Match or approval.
    assertThat(docs.string("$.paths['/api/v1/auth/sign-in'].post['x-scope']")).isEqualTo("PUBLIC");
    assertThat(docs.json().read("$.paths['/api/v1/auth/sign-in'].post.security", List.class))
        .isEmpty();
    assertThat(
            docs.json().read("$.paths['/api/v1/products'].get.parameters[*]['$ref']", List.class))
        .doesNotContain("#/components/parameters/ifMatch", "#/components/parameters/approval");

    // A step-up route says so.
    List<Object> stepUp = docs.json().read("$.paths..[?(@['x-step-up'] == true)]");
    assertThat(stepUp).isNotEmpty();

    // RFC 9457 problems and the common headers are described once, in the components.
    assertThat(docs.json().read("$.components.schemas.Problem.properties", Map.class))
        .containsKeys("type", "title", "status", "detail", "reason", "field", "correlationId");
    assertThat(docs.json().read("$.components.responses.Problem.content", Map.class))
        .containsKey("application/problem+json");
    assertThat(docs.json().read("$.components.parameters", Map.class))
        .containsKeys(
            "tenant", "branch", "branchRequired", "idempotencyKey", "approval", "ifMatch");
    assertThat(docs.string("$.components.parameters.tenant.name")).isEqualTo("X-Tenant-Id");
    assertThat(docs.string("$.components.parameters.ifMatch.name")).isEqualTo("If-Match");
    assertThat(docs.string("$.components.parameters.approval.name")).isEqualTo("X-Approval");

    // Every operation of the API declares its scope (a handler without @Access is refused).
    List<String> scopes = docs.json().read("$.paths[*][*]['x-scope']");
    List<Object> operations = docs.json().read("$.paths[*][*].operationId");
    assertThat(scopes).hasSameSizeAs(operations).doesNotContainNull();
    assertThat(operations).doesNotHaveDuplicates();
  }

  // -------------------------------------------------------------------------- STD-10 ETag/412

  @Test
  @DisplayName("a product: ETag on GET, a stale If-Match is 412, the right one saves")
  void productIfMatch() {
    String product = product("Ibuprofen 400mg", null).expect(201).string("$.data.uid");
    Response read = admin.get("/api/v1/products/" + product).expect(200);
    long version = read.read("$.data.version", Long.class);
    assertThat(read.header("ETag")).isEqualTo("\"" + version + "\"");

    String body = productBody("Ibuprofen 400mg tablets", null);
    // Someone else saved first.
    Response stale =
        admin.put("/api/v1/products/" + product, body, "If-Match", "\"" + (version + 5) + "\"");
    assertThat(stale.expect(412).string("$.reason")).isEqualTo("precondition_failed");
    assertThat(stale.read("$.currentVersion", Long.class)).isEqualTo(version);
    assertThat(stale.header("Content-Type")).startsWith("application/problem+json");

    // The right tag (the body may leave its version out then): saved, with the next tag.
    Response saved =
        admin.put("/api/v1/products/" + product, body, "If-Match", read.header("ETag"));
    saved.expect(200);
    long next = saved.read("$.data.version", Long.class);
    assertThat(next).isGreaterThan(version);
    assertThat(saved.header("ETag")).isEqualTo("\"" + next + "\"");

    // The old tag is now stale; a weak one of the current version and "*" match.
    admin.put("/api/v1/products/" + product, body, "If-Match", "\"" + version + "\"").expect(412);
    admin.put("/api/v1/products/" + product, body, "If-Match", "W/\"" + next + "\"").expect(200);
    admin.put("/api/v1/products/" + product, body, "If-Match", "*").expect(200);

    // Without If-Match (older clients) the body's version still guards it, 409 when stale.
    String versioned = productBody("Ibuprofen 400mg", version);
    assertThat(admin.put("/api/v1/products/" + product, versioned).expect(409).string("$.reason"))
        .isEqualTo("stale_version");
  }

  @Test
  @DisplayName("customers, suppliers, settings, roles, branches and plans honour If-Match too")
  void otherResourcesIfMatch() {
    String customer =
        admin
            .post("/api/v1/customers", "{\"name\":\"Mama Asha\",\"phone\":\"+255700100200\"}")
            .expect(201)
            .string("$.data.uid");
    conditional(
        "/api/v1/customers/" + customer,
        "/api/v1/customers/" + customer,
        "{\"name\":\"Mama Asha Juma\",\"phone\":\"+255700100200\"}");
    conditional(
        "/api/v1/customers/" + customer,
        "/api/v1/customers/" + customer + "/credit-limit",
        "{\"creditLimit\":20000}");

    String supplier =
        admin
            .post("/api/v1/suppliers", "{\"name\":\"Kariakoo Pharma\",\"paymentTerms\":\"COD\"}")
            .expect(201)
            .string("$.data.uid");
    conditional(
        "/api/v1/suppliers/" + supplier,
        "/api/v1/suppliers/" + supplier,
        "{\"name\":\"Kariakoo Pharma Ltd\",\"paymentTerms\":\"COD\"}");

    conditional("/api/v1/settings", "/api/v1/settings", "{\"receiptFooter\":\"Asante\"}");

    String role =
        admin
            .post(
                "/api/v1/roles",
                "{\"code\":\"STOCKIST\",\"name\":\"Stockist\",\"permissions\":[\"catalogue.view\"]}")
            .expect(201)
            .string("$.data.uid");
    conditional(
        "/api/v1/roles/" + role,
        "/api/v1/roles/" + role,
        "{\"code\":\"STOCKIST\",\"name\":\"Stock keeper\","
            + "\"permissions\":[\"catalogue.view\",\"inventory.view\"]}");

    String branch = admin.get("/api/v1/branches").expect(200).string("$.data[0].uid");
    conditional(
        "/api/v1/branches/" + branch,
        "/api/v1/branches/" + branch,
        "{\"code\":\"HQ\",\"name\":\"Head Office Mwenge\"}");

    String plan =
        admin
            .post(
                "/api/v1/price-lists",
                "{\"name\":\"Clinics\",\"customerType\":\"WHOLESALE\",\"adjustmentPercent\":-5}")
            .expect(201)
            .string("$.data.uid");
    conditional(
        "/api/v1/price-lists/" + plan,
        "/api/v1/price-lists/" + plan,
        "{\"name\":\"Clinics and dispensaries\",\"customerType\":\"WHOLESALE\","
            + "\"adjustmentPercent\":-5}");
  }

  /**
   * The resource read at {@code read} has an ETag; a change at {@code change} with a stale If-Match
   * is 412 and changes nothing; with the read tag it saves and answers the next tag; with none it
   * saves too (the header is optional by default).
   */
  private void conditional(String read, String change, String body) {
    Response before = admin.get(read).expect(200);
    long version = before.read("$.data.version", Long.class);
    assertThat(before.header("ETag")).as(read).isEqualTo("\"" + version + "\"");

    Response stale = admin.put(change, body, "If-Match", "\"" + (version + 1) + "\"");
    assertThat(stale.expect(412).string("$.reason")).as(change).isEqualTo("precondition_failed");
    assertThat(stale.read("$.currentVersion", Long.class)).isEqualTo(version);
    assertThat(admin.get(read).expect(200).read("$.data.version", Long.class))
        .as("a refused change leaves %s as it was", read)
        .isEqualTo(version);

    Response saved = admin.put(change, body, "If-Match", before.header("ETag")).expect(200);
    long next = saved.read("$.data.version", Long.class);
    assertThat(next).as(change).isGreaterThan(version);
    assertThat(saved.header("ETag")).isEqualTo("\"" + next + "\"");
    assertThat(admin.get(read).expect(200).header("ETag")).isEqualTo("\"" + next + "\"");

    // Someone who saved from the first read now holds a stale tag.
    admin.put(change, body, "If-Match", before.header("ETag")).expect(412);
    admin.put(change, body).expect(200);
  }

  /** With pharmacy.http.require-if-match=true a change without If-Match is 428. */
  @Nested
  @TestPropertySource(properties = "pharmacy.http.require-if-match=true")
  class WhenIfMatchIsRequired extends IntegrationTest {

    @Test
    @DisplayName("a change without If-Match is 428 precondition_required; with it, it saves")
    void requiresIfMatch() {
      Api anonymous = api();
      Api root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
      String tenant =
          root.get("/api/v1/platform/tenants?q=Standards")
              .expect(200)
              .string("$.data.items[0].uid");
      Api support = root.in(tenant, null);
      Response settings = support.get("/api/v1/settings").expect(200);
      Response missing = support.put("/api/v1/settings", "{\"receiptFooter\":\"Karibu\"}");
      assertThat(missing.expect(428).string("$.reason")).isEqualTo("precondition_required");
      assertThat(missing.header("Content-Type")).startsWith("application/problem+json");
      support
          .put(
              "/api/v1/settings",
              "{\"receiptFooter\":\"Karibu\"}",
              "If-Match",
              settings.header("ETag"))
          .expect(200);
      // Only changes of edited resources need it: a new record does not.
      support
          .post("/api/v1/customers", "{\"name\":\"Bwana Said\",\"phone\":\"+255700300400\"}")
          .expect(201);
    }
  }

  // ------------------------------------------------------------------------------ STD-18 GTIN

  @Test
  @DisplayName("a barcode needs its GS1 check digit; an old wrong one stays readable and editable")
  void gtinCheckDigit() {
    // EAN-13, UPC-A (GTIN-12), EAN-8 and GTIN-14 with their check digits.
    for (String good : List.of("5901234123457", "036000291452", "96385074", "10614141000415")) {
      assertThat(Gtin.valid(good)).as(good).isTrue();
    }
    Response wrong = product("Cetirizine 10mg", "6001234567890");
    assertThat(wrong.expect(422).string("$.reason")).isEqualTo("invalid_gtin");
    assertThat(wrong.string("$.field")).isEqualTo("gtin");
    assertThat(product("Cetirizine 10mg", "60012345678").expect(422).string("$.reason"))
        .isEqualTo("invalid_gtin");
    String good = product("Cetirizine 10mg", "6001234567899").expect(201).string("$.data.gtin");
    assertThat(good).isEqualTo("6001234567899");

    // A product saved before the check with a wrong GTIN: read and changed as it is.
    String old = product("Loratadine 10mg", null).expect(201).string("$.data.uid");
    owner("update catalogue.products set gtin = '6001234567891' where uid = ?::uuid", old);
    Response read = admin.get("/api/v1/products/" + old).expect(200);
    assertThat(read.string("$.data.gtin")).isEqualTo("6001234567891");
    long version = read.read("$.data.version", Long.class);
    String keep =
        productBody("Loratadine 10mg tablets", version)
            .replace("\"taxRatePercent\"", "\"gtin\":\"6001234567891\",\"taxRatePercent\"");
    admin.put("/api/v1/products/" + old, keep).expect(200);
    // Changing it to another wrong one is refused.
    String other =
        productBody("Loratadine 10mg tablets", version + 1)
            .replace("\"taxRatePercent\"", "\"gtin\":\"6001234567892\",\"taxRatePercent\"");
    assertThat(admin.put("/api/v1/products/" + old, other).expect(422).string("$.reason"))
        .isEqualTo("invalid_gtin");
  }

  // -------------------------------------------------------------- STD-18 batch numbers' case

  @Test
  @DisplayName("a batch number in another case is the same batch; the database holds one")
  void batchNumbersIgnoreCase() {
    String product = product("Metformin 500mg", null).expect(201).string("$.data.uid");
    LocalDate expiry = LocalDate.now().plusYears(2);
    opening(product, "MET-26A", expiry, 10).expect(201);
    opening(product, "met-26a", expiry, 5).expect(201);

    List<Object> batches =
        till.get("/api/v1/stock/" + product + "/batches").expect(200).json().read("$.data[*]");
    assertThat(batches).hasSize(1);
    Response stock = till.get("/api/v1/stock/" + product + "/batches").expect(200);
    assertThat(stock.string("$.data[0].batchNumber")).isEqualTo("MET-26A");
    assertThat(stock.read("$.data[0].quantity", Integer.class)).isEqualTo(15);

    // Another expiry under the same number in another case is refused, as for the same case.
    assertThat(opening(product, "Met-26A", expiry.plusDays(1), 1).expect(422).string("$.reason"))
        .isEqualTo("batch_expiry_mismatch");

    // The database refuses a twin too (the unique index on lower(batch_number)).
    assertThat(twinInsertFails(product)).isTrue();
  }

  private Response opening(String product, String batch, LocalDate expiry, int quantity) {
    return till.post(
        "/api/v1/stock/opening",
        """
        {"productUid":"%s","batchNumber":"%s","expiryDate":"%s","quantity":%d,"unitCost":300}
        """
            .formatted(product, batch, expiry, quantity));
  }

  private boolean twinInsertFails(String product) {
    try {
      owner(
          "insert into inventory.batches (uid, tenant_uid, created_at, created_by, product_uid,"
              + " batch_number, expiry_date, unit_cost) select gen_random_uuid(), tenant_uid,"
              + " now(), 'test', product_uid, 'Met-26a', expiry_date, unit_cost from"
              + " inventory.batches where product_uid = ?::uuid",
          product);
      return false;
    } catch (IllegalStateException failed) {
      return failed.getCause() instanceof SQLException sql && "23505".equals(sql.getSQLState());
    }
  }

  // ---------------------------------------------------------------------------------- helpers

  private Response product(String name, String gtin) {
    String body =
        gtin == null
            ? productBody(name, null)
            : productBody(name, null)
                .replace("\"taxRatePercent\"", "\"gtin\":\"" + gtin + "\",\"taxRatePercent\"");
    return admin.post("/api/v1/products", body);
  }

  private static String productBody(String name, Long version) {
    return """
        {"name":"%s","genericName":"%s","dosageForm":"TABLET","schedule":"OTC",
         "sellingPrice":500,"costPrice":300,"taxRatePercent":0%s}
        """
        .formatted(name, name, version == null ? "" : ",\"version\":" + version);
  }

  /** A statement as the schema owner, in this pharmacy (row-level security is forced on it too). */
  private void owner(String statement, Object... args) {
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
}
