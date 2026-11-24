package tz.co.pharmacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static tz.co.pharmacy.support.Api.eventually;
import static tz.co.pharmacy.support.Api.eventuallyValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.Row;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tz.co.pharmacy.delivery.api.Spreadsheet;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.support.Api;
import tz.co.pharmacy.support.IntegrationTest;
import tz.co.pharmacy.support.Roots;
import tz.co.pharmacy.support.Staff;

/**
 * The data lifecycle, hardened after the adversarial review of 23 Nov 2026: imports read within
 * limits (a zip bomb is refused, the API lives on), exports neutralise formulas, the full export is
 * a background job with a signed link (one at a time, taken over when its worker dies, expired by
 * the retention), a legal hold stops the purge and the audit drop, the definer functions trust no
 * date from their caller, erasure takes the pharmacy's events and retries the file deletes it could
 * not do, and the export leaves out root's notes and shows former staff as they were.
 */
class DataLifecycleTest extends IntegrationTest {

  private static final byte[] PDF =
      "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);

  private static final String PERIODS =
      "{\"sessions\":30,\"codes\":7,\"invitations\":90,\"messages\":180,\"inbox\":180,"
          + "\"deliveries\":90,\"events\":7,\"exports\":30}";

  @Autowired DataSource dataSource;
  @Autowired ObjectStorage storage;

  @Value("${pharmacy.files.local.root:target/files}")
  String filesRoot;

  private Api root;

  record Pharmacy(String uid, String code, Api admin, Api anonymous) {}

  private Pharmacy pharmacy(String prefix) {
    Api anonymous = api();
    root = anonymous.as(anonymous.signIn("root@test.local", "Root#Test2026"));
    String code = prefix + UUID.randomUUID().toString().substring(0, 4).toUpperCase();
    String email = code.toLowerCase() + "@test.local";
    String tenant =
        root.post(
                "/api/v1/platform/tenants",
                """
                {"code":"%s","name":"Life %s","legalName":"Life %s Ltd","country":"TZ",
                 "currency":"TZS","timezone":"Africa/Dar_es_Salaam",
                 "headOffice":{"code":"HQ","name":"Head Office"},
                 "admin":{"name":"Admin","email":"%s","password":"Admin#2026x-strong"}}
                """
                    .formatted(code, code, code, email))
            .expect(201)
            .string("$.data.uid");
    Api admin = anonymous.as(anonymous.signIn(email, "Admin#2026x-strong")).in(tenant, null);
    return new Pharmacy(tenant, code, admin, anonymous);
  }

  // ------------------------------------------------------------------ INT-1

  @Test
  @DisplayName(
      "INT-1: a zip bomb, too many parts, a huge cell or columns are refused; the API lives")
  void importsReadWithinLimits() throws IOException {
    Pharmacy p = pharmacy("IM");
    Api admin = p.admin();

    // 64 MB of zeros in one part, about 64 KB on the wire: refused on its ratio.
    byte[] ratioBomb = zip(Map.of("xl/worksheets/sheet1.xml", new byte[1 << 20]), 64);
    assertThat(ratioBomb.length).isLessThan(200_000);
    assertThat(reason(admin, "/api/v1/products/import/check", "bomb.xlsx", ratioBomb))
        .isEqualTo("file_too_compressed");
    assertThat(reason(admin, "/api/v1/products/import", "bomb.xlsx", ratioBomb))
        .isEqualTo("file_too_compressed");

    // Sixty parts of 1 MB that each expand less than 100 times, 60 MB in all: refused on the total.
    byte[] sparse = new byte[1 << 20];
    Random random = new Random(7);
    for (int i = 0; i < sparse.length; i += 101) {
      sparse[i] = (byte) (1 + random.nextInt(255));
    }
    Map<String, byte[]> parts = new LinkedHashMap<>();
    for (int i = 0; i < 60; i++) {
      parts.put("xl/media/image" + i + ".bin", sparse);
    }
    ByteArrayOutputStream ratios = new ByteArrayOutputStream();
    byte[] sizeBomb = zip(parts, 1, ratios);
    assertThat(sizeBomb.length).as("fits the 10 MB upload").isLessThan(9 * 1024 * 1024);
    assertThat(ratios.toString(StandardCharsets.US_ASCII).split(","))
        .allSatisfy(ratio -> assertThat(Double.parseDouble(ratio)).isLessThan(100));
    assertThat(reason(admin, "/api/v1/products/import/check", "big.xlsx", sizeBomb))
        .isEqualTo("file_too_large_uncompressed");

    // A thousand and one parts.
    Map<String, byte[]> many = new LinkedHashMap<>();
    for (int i = 0; i <= 1_000; i++) {
      many.put("xl/part" + i + ".xml", new byte[] {'x'});
    }
    assertThat(reason(admin, "/api/v1/customers/import/check", "many.xlsx", zip(many, 1)))
        .isEqualTo("file_too_many_entries");

    // A cell longer than Excel allows, and something far to the right.
    String longCell = "Code,Name\r\nP1," + "x".repeat(40_000) + "\r\n";
    assertThat(
            reason(
                admin,
                "/api/v1/products/import/check",
                "long.csv",
                longCell.getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("cell_too_long");
    String wide = "Code,Name\r\nP1," + "y,".repeat(250) + "z\r\n";
    assertThat(
            reason(
                admin,
                "/api/v1/products/import/check",
                "wide.csv",
                wide.getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("too_many_columns");

    // The API is still there for everyone, and a real workbook still reads.
    admin.get("/api/v1/auth/me").expect(200);
    byte[] template = admin.download("/api/v1/products/import/template");
    admin.upload("/api/v1/products/import/check", "template.xlsx", template).expect(200);
  }

  private static String reason(Api admin, String path, String name, byte[] content) {
    return admin.upload(path, name, content).expect(422).string("$.reason");
  }

  // ------------------------------------------------------------------ INT-11

  record Line(String text, BigDecimal amount) {}

  @Test
  @DisplayName(
      "INT-11: text that looks like a formula gets a ' in CSV and xlsx; numbers stay numbers")
  void exportsNeutraliseFormulas() throws IOException {
    List<Spreadsheet.Column<Line>> columns =
        List.of(
            Spreadsheet.column("Text", "Maandishi", Line::text),
            Spreadsheet.column("Amount", "Kiasi", Line::amount));
    List<Line> rows =
        List.of(
            new Line("=HYPERLINK(\"http://evil\",\"x\")", new BigDecimal("-5.50")),
            new Line("+255700000001", new BigDecimal("3")),
            new Line("-2+3", new BigDecimal("-1")),
            new Line("@SUM(A1)", BigDecimal.ZERO),
            new Line("\tcmd", BigDecimal.ONE),
            new Line("Plain - text", BigDecimal.TEN));
    String csv =
        new String(
            Spreadsheet.answer("lines", "csv", null, ZoneOffset.UTC, columns, rows).getBody(),
            StandardCharsets.UTF_8);
    assertThat(csv)
        .contains("\"'=HYPERLINK(\"\"http://evil\"\",\"\"x\"\")\",\"-5.50\"")
        .contains("\"'+255700000001\",\"3\"")
        .contains("\"'-2+3\",\"-1\"")
        .contains("\"'@SUM(A1)\"")
        .contains("\"'\tcmd\"")
        .contains("\"Plain - text\",\"10\"")
        .doesNotContain("'-5.50", "'-1");

    byte[] xlsx =
        Spreadsheet.answer("lines", "xlsx", null, ZoneOffset.UTC, columns, rows).getBody();
    try (ReadableWorkbook workbook = new ReadableWorkbook(new ByteArrayInputStream(xlsx))) {
      List<Row> sheet = workbook.getFirstSheet().read();
      assertThat(sheet.get(1).getCell(0).getText()).isEqualTo("'=HYPERLINK(\"http://evil\",\"x\")");
      assertThat(sheet.get(1).getCell(1).getType()).isEqualTo(CellType.NUMBER);
      assertThat(sheet.get(1).getCell(1).asNumber()).isEqualByComparingTo("-5.5");
      assertThat(sheet.get(3).getCell(0).getText()).isEqualTo("'-2+3");
      assertThat(sheet.get(3).getCell(1).getType()).isEqualTo(CellType.NUMBER);
      assertThat(sheet.get(6).getCell(0).getText()).isEqualTo("Plain - text");
    }

    // Through the API: a customer's name, in the list export and in the full export.
    Pharmacy p = pharmacy("FX");
    p.admin()
        .post(
            "/api/v1/customers",
            "{\"name\":\"=HYPERLINK(\\\"http://evil\\\",\\\"x\\\")\",\"phone\":\"+255700000131\"}")
        .expect(201);
    assertThat(p.admin().get("/api/v1/customers/export?format=csv").expect(200).body())
        .contains("\"'=HYPERLINK(")
        .contains("\"'+255700000131\"");
    Map<String, String> zip = unzip(p.admin().download("/api/v1/data-export"));
    assertThat(zip.get("partners/customers.csv")).contains("'=HYPERLINK(");
  }

  // ------------------------------------------------------------------ INT-7, PERF-12

  @Test
  @DisplayName(
      "INT-7: the full export is a job: 202, one at a time, a signed link, taken over, expired")
  void exportIsABackgroundJob() throws IOException {
    Pharmacy p = pharmacy("EX");
    Api admin = p.admin();
    for (int i = 0; i < 30; i++) {
      admin
          .post(
              "/api/v1/customers",
              "{\"name\":\"Customer %02d\",\"phone\":\"+2557000002%02d\"}".formatted(i, i))
          .expect(201);
    }
    admin
        .upload(
            "/api/v1/files?ownerType=TENANT&ownerUid=" + p.uid(),
            "licence.pdf",
            "application/pdf",
            PDF)
        .expect(201);

    // Only someone who may manage the pharmacy's settings takes its data out.
    Staff.Login cashier =
        Staff.login(admin, p.anonymous(), "Till Cashier", "CASHIER", "Cashier#2026x-strong");
    Api till = p.anonymous().as(p.anonymous().signIn(cashier.signIn(), "Cashier#2026x-strong"));
    till.in(p.uid(), null).post("/api/v1/data-export", "{}").expect(403);

    // One at a time: while one is open, a second (or the old download) is refused.
    String open = UUID.randomUUID().toString();
    sql(
        p.uid(),
        "insert into platform.data_exports (uid, tenant_uid, mode, status, requested_by, attempts,"
            + " started_at, heartbeat_at) values (?::uuid, ?::uuid, 'STORED', 'RUNNING', 'test', 1,"
            + " now(), now())",
        open,
        p.uid());
    Api.Response busy = admin.post("/api/v1/data-export", "{}").expect(409);
    assertThat(busy.string("$.reason")).isEqualTo("export_running");
    assertThat(busy.string("$.uid")).isEqualTo(open);
    assertThat(admin.get("/api/v1/data-export").expect(409).string("$.reason"))
        .isEqualTo("export_running");
    sql(p.uid(), "update platform.data_exports set status = 'FAILED' where uid = ?::uuid", open);

    // Started: 202 at once; the worker builds it; then a link valid 15 minutes.
    Api.Response started = admin.post("/api/v1/data-export", "{}").expect(202);
    String first = started.string("$.data.uid");
    assertThat(started.string("$.data.status")).isIn("PENDING", "RUNNING");
    Api.Response ready = ready(admin, first);
    assertThat(ready.read("$.data.sizeBytes", Long.class)).isPositive();
    Instant linkExpires = Instant.parse(ready.string("$.data.downloadExpiresAt"));
    assertThat(linkExpires).isBetween(Instant.now(), Instant.now().plusSeconds(16 * 60));
    Instant kept = Instant.parse(ready.string("$.data.expiresAt"));
    assertThat(kept).isAfter(Instant.now().plusSeconds(6 * 24 * 3600));
    String link = ready.string("$.data.downloadUrl");
    assertThat(link).contains("/exports/").contains(p.code() + "-export-");

    // Anyone holding the link (and only the link) downloads it: a browser follows it as is.
    Map<String, String> zip = unzip(api().download(link));
    assertThat(zip).containsKeys("manifest.json", "partners/customers.csv", "access/users.csv");
    // Read in pages of 25 rows (the test setting), every row once.
    assertThat(zip.get("partners/customers.csv").split("\r\n")).hasSize(31);
    assertThat(zip.get("partners/customers.csv")).contains("Customer 00", "Customer 29");
    assertThat(zip.get("manifest.json")).contains("consistency", "startedAt");
    assertThat(zip.keySet()).anyMatch(name -> name.endsWith("licence.pdf"));
    api().get(link.replaceAll("sig=[0-9a-f]+", "sig=" + "0".repeat(64))).expect(403);

    // The archive is not one of the pharmacy's files: no file route reaches it.
    String fileUid =
        value(
            p.uid(), "select file_uid::text from platform.data_exports where uid = ?::uuid", first);
    admin.get("/api/v1/files/" + fileUid + "/url").expect(404);
    admin.delete("/api/v1/files/" + fileUid).expect(404);

    // A second export: allowed now, and it does not hold the first one.
    String second = admin.post("/api/v1/data-export", "{}").expect(202).string("$.data.uid");
    Map<String, String> again =
        unzip(api().download(ready(admin, second).string("$.data.downloadUrl")));
    assertThat(again.keySet()).noneMatch(name -> name.endsWith(".zip"));

    // A worker that dies mid-way: another takes the job over once its heartbeat is stale.
    sql(
        p.uid(),
        "update platform.data_exports set status = 'RUNNING', file_uid = null,"
            + " heartbeat_at = now() - interval '1 hour', attempts = 1 where uid = ?::uuid",
        second);
    Api.Response retaken = ready(admin, second);
    assertThat(
            value(
                p.uid(),
                "select attempts::text from platform.data_exports where uid = ?::uuid",
                second))
        .isEqualTo("2");
    assertThat(retaken.string("$.data.downloadUrl")).isNotNull();
    // …and one out of attempts fails for good, freeing the pharmacy's slot.
    String third = admin.post("/api/v1/data-export", "{}").expect(202).string("$.data.uid");
    ready(admin, third);
    sql(
        p.uid(),
        "update platform.data_exports set status = 'RUNNING', heartbeat_at = now() - interval '1"
            + " hour', attempts = 3 where uid = ?::uuid",
        third);
    String failed =
        eventuallyValue(
            () -> {
              Api.Response r = admin.get("/api/v1/data-export/" + third).expect(200);
              return "FAILED".equals(r.string("$.data.status")) ? r.string("$.data.error") : null;
            });
    assertThat(failed).contains("start a new one");
    admin.get("/api/v1/data-export/" + UUID.randomUUID()).expect(404);

    // Expired after its days: the retention deletes the file, the job says EXPIRED.
    String key =
        value(
            p.uid(),
            "select f.storage_key from files.files f join platform.data_exports e"
                + " on e.file_uid = f.uid where e.uid = ?::uuid",
            first);
    assertThat(storage.exists(key)).isTrue();
    sql(
        p.uid(),
        "update platform.data_exports set expires_at = now() - interval '1 minute' where uid ="
            + " ?::uuid",
        first);
    Api.Response run = root.post("/api/v1/platform/retention/run", "{}").expect(200);
    assertThat(run.read("$.data['exports expired']", Long.class)).isPositive();
    assertThat(storage.exists(key)).isFalse();
    Api.Response gone = admin.get("/api/v1/data-export/" + first).expect(200);
    assertThat(gone.string("$.data.status")).isEqualTo("EXPIRED");
    assertThat(gone.read("$.data.downloadUrl", String.class)).isNull();
  }

  private static Api.Response ready(Api admin, String job) {
    return eventuallyValue(
        () -> {
          Api.Response status = admin.get("/api/v1/data-export/" + job).expect(200);
          return "READY".equals(status.string("$.data.status")) ? status : null;
        });
  }

  // ------------------------------------------------------------------ INT-8

  @Test
  @DisplayName("INT-8: a legal hold keeps the pharmacy's rows and its audit months from retention")
  void legalHoldStopsRetention() {
    Pharmacy held = pharmacy("LH");
    Pharmacy free = pharmacy("LF");
    for (Pharmacy p : List.of(held, free)) {
      invite(p, "old." + p.code().toLowerCase() + "@test.local");
      sql(
          p.uid(),
          "update access.invitations set expires_at = now() - interval '100 days' where email = ?",
          "old." + p.code().toLowerCase() + "@test.local");
    }
    root.post("/api/v1/platform/tenants/" + held.uid() + "/legal-hold", "{\"reason\":\"Case 1\"}")
        .expect(200);
    // Two old audit months: one with an entry of the held pharmacy, one with the other's only.
    sql(null, "select audit.ensure_partitions('2014-03-01'::date, 1)");
    auditEntry(held.uid(), "2014-03-10T10:00:00Z");
    auditEntry(free.uid(), "2014-04-10T10:00:00Z");

    root.post("/api/v1/platform/retention/run", "{}").expect(200);
    assertThat(invitations(held)).isOne();
    assertThat(invitations(free)).isZero();
    assertThat(partition("entries_2014_03")).as("held pharmacy's month").isTrue();
    assertThat(partition("entries_2014_04")).isFalse();

    // Lifted: the next run takes what it kept.
    Roots.twoRoots(root, api(), "LIFT_LEGAL_HOLD", held.uid(), null).expect(200);
    root.post("/api/v1/platform/retention/run", "{}").expect(200);
    assertThat(invitations(held)).isZero();
    assertThat(partition("entries_2014_03")).isFalse();
  }

  private void invite(Pharmacy p, String email) {
    List<String> roles =
        p.admin()
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == 'CASHIER')].uid");
    p.admin()
        .post(
            "/api/v1/users/invitations",
            "{\"name\":\"Invitee\",\"email\":\"%s\",\"roleUid\":\"%s\",\"allBranches\":true}"
                .formatted(email, roles.getFirst()))
        .expect(201);
  }

  private long invitations(Pharmacy p) {
    return Long.parseLong(
        value(
            p.uid(),
            "select count(*)::text from access.invitations where email = ?",
            "old." + p.code().toLowerCase() + "@test.local"));
  }

  private void auditEntry(String tenantUid, String at) {
    sql(
        tenantUid,
        "insert into audit.entries (uid, at, tenant_uid, actor, action)"
            + " values (gen_random_uuid(), ?::timestamptz, ?::uuid, 'test', 'TEST')",
        at,
        tenantUid);
  }

  private boolean partition(String name) {
    return !"0".equals(value(null, "select count(*)::text from pg_class where relname = ?", name));
  }

  // ------------------------------------------------------------------ INT-10, ISO-5

  @Test
  @DisplayName(
      "INT-10: the definer functions count from now() at the latest and keep 2 years of audit")
  void definerFunctionsTrustNoDate() {
    Pharmacy p = pharmacy("DF");
    String fresh = "fresh." + p.code().toLowerCase() + "@test.local";
    invite(p, fresh);

    // A date far in the future, and negative periods, delete nothing that is still current.
    sql(null, "select platform.purge_expired('2100-01-01'::timestamptz, ?::jsonb, 10000)", PERIODS);
    sql(
        null,
        "select platform.purge_expired(now(), ?::jsonb, 10000)",
        PERIODS.replaceAll(":(\\d+)", ":-$1"));
    p.admin().get("/api/v1/auth/me").expect(200);
    assertThat(
            value(p.uid(), "select count(*)::text from access.invitations where email = ?", fresh))
        .isEqualTo("1");

    // The audit trail keeps at least two years, whatever date is asked for.
    LocalDate lastYear = LocalDate.now(ZoneOffset.UTC).minusMonths(13).withDayOfMonth(1);
    sql(null, "select audit.ensure_partitions(?::date, 0)", lastYear.toString());
    String name = "entries_%04d_%02d".formatted(lastYear.getYear(), lastYear.getMonthValue());
    assertThat(partition(name)).isTrue();
    sql(null, "select audit.drop_partitions_before('2100-01-01'::date)");
    assertThat(partition(name)).isTrue();
    LocalDate now = LocalDate.now(ZoneOffset.UTC);
    assertThat(partition("entries_%04d_%02d".formatted(now.getYear(), now.getMonthValue())))
        .isTrue();

    // The cooling-off is the database's: a date in the future does not skip it.
    root.post("/api/v1/platform/tenants/" + p.uid() + "/close", "{\"reason\":\"Test\"}")
        .expect(200);
    assertThatThrownBy(
            () ->
                sql(
                    null,
                    "select platform.erase_tenant(?::uuid, '2100-01-01'::timestamptz)",
                    p.uid()))
        .hasMessageContaining("cooling-off");
    assertThat(root.get("/api/v1/platform/tenants/" + p.uid()).expect(200).string("$.data.status"))
        .isEqualTo("CLOSED");
  }

  // ------------------------------------------------------------------ INT-16

  @Test
  @DisplayName("INT-16: erasure takes the pharmacy's events; a file the store refused is retried")
  void erasureTakesEventsAndRetriesFiles() throws IOException {
    Pharmacy leaving = pharmacy("EV");
    Pharmacy staying = pharmacy("SV");
    for (Pharmacy p : List.of(leaving, staying)) {
      p.admin()
          .post("/api/v1/customers", "{\"name\":\"Asha\",\"phone\":\"+255700000141\"}")
          .expect(201);
    }
    leaving
        .admin()
        .upload(
            "/api/v1/files?ownerType=TENANT&ownerUid=" + leaving.uid(),
            "licence.pdf",
            "application/pdf",
            PDF)
        .expect(201);
    eventually(() -> events(leaving.uid()) > 0 && events(staying.uid()) > 0);
    // One of its events not delivered yet (an incomplete publication would replay at restart).
    sql(
        null,
        "update platform.event_publication set completion_date = null, status = 'FAILED'"
            + " where id = (select id from platform.event_publication where serialized_event like ?"
            + " limit 1)",
        "%\"tenantUid\":\"" + leaving.uid() + "\"%");
    long stayingEvents = events(staying.uid());

    String tenants = "/api/v1/platform/tenants/" + leaving.uid();
    root.post(tenants + "/close", "{\"reason\":\"Leaving\"}").expect(200);
    sql(
        null,
        "update tenancy.tenants set erase_after = now() - interval '1 minute' where uid = ?::uuid",
        leaving.uid());

    // The store refuses to delete its file (here: a directory with something in it at its key).
    String key =
        value(
            leaving.uid(),
            "select storage_key from files.files where tenant_uid = ?::uuid and owner_type ="
                + " 'TENANT'",
            leaving.uid());
    Path stored = Path.of(filesRoot).toAbsolutePath().normalize().resolve(key);
    Files.delete(stored);
    Files.createDirectories(stored);
    Files.writeString(stored.resolve("blocker"), "x");

    Api.Response erased =
        Roots.twoRoots(root, api(), "ERASE_TENANT", leaving.uid(), leaving.code()).expect(200);
    assertThat(erased.read("$.data.rows['platform.event_publication']", Long.class)).isPositive();
    assertThat(erased.read("$.data.rows['files (queued for a retry)']", Long.class)).isOne();
    assertThat(events(leaving.uid())).isZero();
    assertThat(events(staying.uid())).isEqualTo(stayingEvents);
    assertThat(
            value(
                null,
                "select count(*)::text from files.pending_deletes where storage_key = ?",
                key))
        .isEqualTo("1");

    // The store is back (the object is there to delete): the nightly retention deletes it.
    Files.delete(stored.resolve("blocker"));
    Files.delete(stored);
    Files.write(stored, PDF);
    Api.Response run = root.post("/api/v1/platform/retention/run", "{}").expect(200);
    assertThat(run.read("$.data['file deletes retried']", Long.class)).isPositive();
    assertThat(Files.exists(stored)).isFalse();
    assertThat(
            value(
                null,
                "select count(*)::text from files.pending_deletes where storage_key = ?",
                key))
        .isEqualTo("0");
  }

  private long events(String tenantUid) {
    return Long.parseLong(
        value(
            null,
            "select count(*)::text from platform.event_publication where serialized_event like ?",
            "%\"tenantUid\":\"" + tenantUid + "\"%"));
  }

  // ------------------------------------------------------------------ INT-17, ISO-6

  @Test
  @DisplayName(
      "INT-17, ISO-6: the export leaves root's notes out and shows former staff as they were")
  void exportLeavesOutRootNotesAndShowsFormerStaff() throws IOException {
    Pharmacy p = pharmacy("RN");
    Staff.Login neema =
        Staff.login(p.admin(), p.anonymous(), "Neema Old", "CASHIER", "Neema#2026xx-strong");
    List<String> roles =
        p.admin()
            .get("/api/v1/roles")
            .expect(200)
            .json()
            .read("$.data[?(@.code == 'CASHIER')].uid");
    p.admin()
        .put(
            "/api/v1/users/" + neema.membershipUid(),
            "{\"roleUid\":\"%s\",\"allBranches\":true,\"active\":false}"
                .formatted(roles.getFirst()))
        .expect(200);
    // She changes her name afterwards, in her own account.
    sql(
        p.uid(),
        "update access.users set name = 'Neema New' where uid = (select user_uid from"
            + " access.memberships where uid = ?::uuid)",
        neema.membershipUid());

    String tenants = "/api/v1/platform/tenants/" + p.uid();
    root.post(tenants + "/legal-hold", "{\"reason\":\"HOLD-NOTE-7731\"}").expect(200);
    root.post(tenants + "/close", "{\"reason\":\"CLOSE-NOTE-7731\"}").expect(200);

    // Closed: its admin still takes the data out, through the job too.
    String job = p.admin().post("/api/v1/data-export", "{}").expect(202).string("$.data.uid");
    Map<String, String> zip =
        unzip(api().download(ready(p.admin(), job).string("$.data.downloadUrl")));

    String tenantCsv = zip.get("tenancy/tenants.csv");
    String header = tenantCsv.substring(0, tenantCsv.indexOf("\r\n"));
    assertThat(header).contains("code", "status").doesNotContain("status_reason", "legal_hold");
    assertThat(String.join("\n", zip.values())).doesNotContain("HOLD-NOTE-7731", "CLOSE-NOTE-7731");

    String people = zip.get("access/users.csv");
    assertThat(people).contains("Neema Old").doesNotContain("Neema New");
    Roots.twoRoots(root, api(), "LIFT_LEGAL_HOLD", p.uid(), null).expect(200);
  }

  // ------------------------------------------------------------------ helpers

  /** A ZIP of the given parts, each written {@code repeat} times; ratios as "r1,r2,…". */
  private static byte[] zip(Map<String, byte[]> parts, int repeat, ByteArrayOutputStream ratios)
      throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
      zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
      zip.write("<Types/>".getBytes(StandardCharsets.US_ASCII));
      zip.closeEntry();
      List<String> seen = new ArrayList<>();
      for (Map.Entry<String, byte[]> part : parts.entrySet()) {
        ZipEntry entry = new ZipEntry(part.getKey());
        zip.putNextEntry(entry);
        for (int i = 0; i < repeat; i++) {
          zip.write(part.getValue());
        }
        zip.closeEntry();
        seen.add(String.valueOf((double) entry.getSize() / Math.max(1, entry.getCompressedSize())));
      }
      if (ratios != null) {
        ratios.write(String.join(",", seen).getBytes(StandardCharsets.US_ASCII));
      }
    }
    return bytes.toByteArray();
  }

  private static byte[] zip(Map<String, byte[]> parts, int repeat) throws IOException {
    return zip(parts, repeat, null);
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

  /** Runs a statement as the application role, as the pharmacy (or the platform when null). */
  private void sql(String tenantUid, String statement, Object... args) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      scope(c, tenantUid);
      try (PreparedStatement s = c.prepareStatement(statement)) {
        for (int i = 0; i < args.length; i++) {
          s.setObject(i + 1, args[i]);
        }
        s.execute();
      }
      c.commit();
    } catch (SQLException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  /** The first column of the first row, as the pharmacy (or the platform when null). */
  private String value(String tenantUid, String query, Object... args) {
    try (Connection c = dataSource.getConnection()) {
      c.setAutoCommit(false);
      scope(c, tenantUid);
      try (PreparedStatement s = c.prepareStatement(query)) {
        for (int i = 0; i < args.length; i++) {
          s.setObject(i + 1, args[i]);
        }
        try (ResultSet r = s.executeQuery()) {
          String found = r.next() ? r.getString(1) : null;
          c.rollback();
          return found;
        }
      }
    } catch (SQLException e) {
      throw new UncheckedIOException(new IOException(e.getMessage(), e));
    }
  }

  private static void scope(Connection c, String tenantUid) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("select set_config('pharmacy.tenant_uid', ?, true)")) {
      s.setString(1, tenantUid == null ? "" : tenantUid);
      s.execute();
    }
  }
}
