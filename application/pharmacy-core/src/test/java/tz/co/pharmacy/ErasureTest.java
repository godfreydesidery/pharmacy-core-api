package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static tz.co.pharmacy.support.Api.eventually;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Roots;

/**
 * A pharmacy leaves: it takes its data out, closes, and after the cooling-off its data is erased,
 * every row and file, append-only tables included, while every other pharmacy stays exactly as it
 * was. A legal hold and the cooling-off both stop the erasure.
 */
class ErasureTest extends IntegrationTest {

  private static final byte[] PDF =
      "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

  @Autowired DataSource dataSource;
  @Autowired ObjectStorage storage;

  private Api root;

  record Pharmacy(String uid, String code, Api admin, Api till) {}

  private Pharmacy pharmacy(String prefix) {
    Api anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    Roots.second(root, anonymous);
    String code = prefix + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Erase %s","legalName":"Erase %s Ltd","country":"TZ",
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
    // Something in every corner: a product, stock, a sale (ledger and stock movements, both
    // append-only), a customer, a file and the audit trail all of that leaves behind.
    String product =
        admin
            .post(
                "/api/v1/products",
                """
                {"name":"Paracetamol 500mg","genericName":"Paracetamol","dosageForm":"TABLET",
                 "schedule":"OTC","productType":"MEDICINE","trackingMode":"BATCH",
                 "sellingPrice":500,"costPrice":200,"taxRatePercent":0}
                """)
            .expect(201)
            .string("$.data.uid");
    till.post(
            "/api/v1/stock/opening",
            """
            {"productUid":"%s","batchNumber":"B-1","expiryDate":"%s","quantity":50,"unitCost":200}
            """
                .formatted(product, LocalDate.now().plusYears(2)))
        .expect(201);
    till.post(
            "/api/v1/sales",
            """
            {"idempotencyKey":"%s","lines":[{"productUid":"%s","quantity":2}],
             "payments":[{"method":"CASH","amount":1000}]}
            """
                .formatted(UUID.randomUUID(), product))
        .expect(201);
    admin
        .post(
            "/api/v1/customers",
            "{\"name\":\"Asha, \\\"Mama\\\" Juma\",\"phone\":\"+255700000111\"}")
        .expect(201);
    admin
        .upload(
            "/api/v1/files?ownerType=PRODUCT&ownerUid=" + product,
            "leaflet.pdf",
            "application/pdf",
            PDF)
        .expect(201);
    Pharmacy made = new Pharmacy(tenant, code, admin, till);
    eventually(() -> rows(made.uid()).getOrDefault("finance.journal_entries", 0L) > 0);
    return made;
  }

  @Test
  @DisplayName("export, close, cooling-off, legal hold, erase: only that pharmacy's data goes")
  void closeAndErase() throws IOException {
    Pharmacy leaving = pharmacy("ER");
    Pharmacy staying = pharmacy("ST");
    Map<String, Long> stayingBefore = rows(staying.uid());
    String tenants = "/api/v1/platform/tenants/" + leaving.uid();

    // Only a closed pharmacy is erased.
    assertThat(
            askErase(leaving.uid(), "{\"confirmCode\":\"" + leaving.code() + "\"}")
                .expect(422)
                .string("$.reason"))
        .isEqualTo("erase_refused");

    root.post(tenants + "/close", "{\"reason\":\"Owner retiring\"}").expect(200);
    Api.Response closed = root.get(tenants).expect(200);
    assertThat(closed.string("$.data.status")).isEqualTo("CLOSED");
    assertThat(closed.string("$.data.eraseAfter")).isNotNull();
    assertThat(root.get(tenants + "/subscription").expect(200).string("$.data.status"))
        .isEqualTo("CANCELLED");

    // Closed: its admin may read and take the data out, nobody may work.
    leaving.admin().get("/api/v1/customers").expect(200);
    assertThat(
            leaving
                .admin()
                .post("/api/v1/customers", "{\"name\":\"Late\",\"phone\":\"+255700000112\"}")
                .expect(403)
                .string("$.reason"))
        .isEqualTo("tenant_closed");
    Map<String, String> zip = unzip(leaving.admin().download("/api/v1/data-export"));
    assertThat(zip).containsKeys("manifest.json", "sales/sales.csv", "access/users.csv");
    assertThat(zip.get("partners/customers.csv")).contains("\"Asha, \"\"Mama\"\" Juma\"");
    assertThat(zip.keySet()).anyMatch(name -> name.startsWith("files/") && name.endsWith(".pdf"));
    assertThat(String.join("\n", zip.values())).doesNotContain("password_hash", "token_hash");
    assertThat(zip.get("access/users.csv")).contains(leaving.code().toLowerCase() + "@test.local");
    // The root console exports the same.
    assertThat(unzip(root.download(tenants + "/export"))).containsKey("manifest.json");

    // Still cooling off.
    assertThat(
            askErase(leaving.uid(), "{\"confirmCode\":\"" + leaving.code() + "\"}")
                .expect(422)
                .string("$.detail"))
        .contains("cooling-off");
    coolingOffEnded(leaving.uid());

    // A legal hold stops it until lifted.
    root.post(tenants + "/legal-hold", "{\"reason\":\"TMDA inquiry 7/2026\"}").expect(200);
    assertThat(
            askErase(leaving.uid(), "{\"confirmCode\":\"" + leaving.code() + "\"}")
                .expect(422)
                .string("$.detail"))
        .contains("legal hold");
    // Lifting the hold needs a second root administrator (FRD-18).
    assertThat(root.delete(tenants + "/legal-hold").expect(403).string("$.reason"))
        .isEqualTo("root_approval_required");
    Roots.twoRoots(root, api(), "LIFT_LEGAL_HOLD", leaving.uid(), null).expect(200);

    // The code must be typed to confirm.
    assertThat(askErase(leaving.uid(), "{\"confirmCode\":\"NOPE\"}").expect(422).string("$.reason"))
        .isEqualTo("confirm_code_mismatch");

    List<String> keys = fileKeys(leaving.uid());
    keys.forEach(key -> assertThat(storage.exists(key)).as(key).isTrue());
    Api.Response erased =
        Roots.twoRoots(root, api(), "ERASE_TENANT", leaving.uid(), leaving.code()).expect(200);
    assertThat(erased.string("$.data.tenant.status")).isEqualTo("ERASED");
    assertThat(erased.read("$.data.rows['finance.journal_entries']", Long.class)).isPositive();
    assertThat(erased.read("$.data.rows['audit.entries']", Long.class)).isPositive();

    // Nothing of it is left but the tombstone and the platform's own billing records.
    Map<String, Long> left = rows(leaving.uid());
    left.remove("tenancy.subscriptions");
    left.remove("tenancy.subscription_invoices");
    assertThat(left).allSatisfy((table, count) -> assertThat(count).as(table).isZero());
    assertThat(keys).isNotEmpty().noneMatch(storage::exists);
    Api.Response tombstone = root.get(tenants).expect(200);
    assertThat(tombstone.string("$.data.code")).isEqualTo(leaving.code());
    assertThat(tombstone.string("$.data.erasedAt")).isNotNull();
    // The other pharmacy is untouched, and keeps working.
    assertThat(rows(staying.uid())).isEqualTo(stayingBefore);
    staying.admin().get("/api/v1/customers").expect(200);
    staying
        .admin()
        .post("/api/v1/customers", "{\"name\":\"Next\",\"phone\":\"+255700000113\"}")
        .expect(201);
    // Erased for good: no way back.
    assertThat(leaving.admin().get("/api/v1/customers").expect(403).string("$.reason"))
        .isIn("tenant_erased", "forbidden");
    root.post(tenants + "/reopen", "{}").expect(422);
    askErase(leaving.uid(), "{\"confirmCode\":\"" + leaving.code() + "\"}").expect(422);
  }

  @Test
  @DisplayName("a dispute's suspension holds the data; a closed pharmacy reopens before erasure")
  void disputeHoldsAndReopen() {
    Pharmacy p = pharmacy("DH");
    String tenants = "/api/v1/platform/tenants/" + p.uid();
    root.post(tenants + "/suspend", "{\"kind\":\"DISPUTE\",\"reason\":\"Case 9/2026\"}")
        .expect(200);
    assertThat(root.get(tenants).expect(200).read("$.data.legalHold", Boolean.class)).isTrue();
    root.post(tenants + "/close", "{\"reason\":\"Lost the case\"}").expect(200);
    coolingOffEnded(p.uid());
    assertThat(
            askErase(p.uid(), "{\"confirmCode\":\"" + p.code() + "\"}")
                .expect(422)
                .string("$.detail"))
        .contains("legal hold");

    root.post(tenants + "/reopen", "{}").expect(200);
    Api.Response reopened = root.get(tenants).expect(200);
    assertThat(reopened.string("$.data.status")).isEqualTo("ACTIVE");
    assertThat(reopened.string("$.data.eraseAfter")).isNull();
    p.admin()
        .post("/api/v1/customers", "{\"name\":\"Back\",\"phone\":\"+255700000114\"}")
        .expect(201);
    assertThat(root.get(tenants + "/subscription").expect(200).string("$.data.status"))
        .isNotEqualTo("CANCELLED");
  }

  // ------------------------------------------------------------------ helpers

  /** Asks for an erasure (FRD-18: a second root administrator then approves). */
  private Api.Response askErase(String tenantUid, String body) {
    String code = body.replaceAll(".*\"confirmCode\":\"([^\"]*)\".*", "$1");
    return root.post(
        "/api/v1/platform/approvals",
        "{\"action\":\"ERASE_TENANT\",\"tenantUid\":\"%s\",\"reason\":\"Test\",\"confirmCode\":\"%s\"}"
            .formatted(tenantUid, code));
  }

  /** Moves the cooling-off into the past (the clock is real in these tests). */
  private void coolingOffEnded(String tenantUid) {
    try (Connection c = dataSource.getConnection();
        PreparedStatement s =
            c.prepareStatement(
                "update tenancy.tenants set erase_after = now() - interval '1 minute' where uid ="
                    + " ?::uuid")) {
      s.setString(1, tenantUid);
      assertThat(s.executeUpdate()).isOne();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Rows per tenant table that this pharmacy can see, as itself. */
  private Map<String, Long> rows(String tenantUid) {
    Map<String, Long> counts = new LinkedHashMap<>();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement s =
          c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        s.setString(1, tenantUid);
        s.execute();
      }
      List<String> tables = new ArrayList<>();
      try (PreparedStatement s =
              c.prepareStatement(
                  "select c.table_schema || '.' || c.table_name from information_schema.columns c"
                      + " join pg_namespace n on n.nspname = c.table_schema"
                      + " join pg_class k on k.relnamespace = n.oid and k.relname = c.table_name"
                      + " where c.column_name = 'tenant_uid' and k.relkind in ('r','p')"
                      + " and not k.relispartition order by 1");
          ResultSet r = s.executeQuery()) {
        while (r.next()) {
          tables.add(r.getString(1));
        }
      }
      for (String table : tables) {
        try (PreparedStatement s =
            c.prepareStatement("select count(*) from " + table + " where tenant_uid = ?::uuid")) {
          s.setString(1, tenantUid);
          try (ResultSet r = s.executeQuery()) {
            r.next();
            counts.put(table, r.getLong(1));
          }
        }
      }
      c.rollback();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return counts;
  }

  /** The pharmacy's file keys in the store, read as the pharmacy. */
  private List<String> fileKeys(String tenantUid) {
    List<String> keys = new ArrayList<>();
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      try (PreparedStatement s =
          c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
        s.setString(1, tenantUid);
        s.execute();
      }
      try (PreparedStatement s =
          c.prepareStatement(
              "select storage_key from files.files where tenant_uid = ?::uuid"
                  + " and storage_key is not null")) {
        s.setString(1, tenantUid);
        try (ResultSet r = s.executeQuery()) {
          while (r.next()) {
            keys.add(r.getString(1));
          }
        }
      }
      c.rollback();
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return keys;
  }

  private static Map<String, String> unzip(byte[] bytes) throws IOException {
    Map<String, String> entries = new LinkedHashMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
      }
    }
    return entries;
  }
}
