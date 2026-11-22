package tz.co.pharmacy.platform.composition;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantData;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Export and erasure of one pharmacy, across every module's tables and the file store. The tables
 * are found from the catalogue (every table with a {@code tenant_uid}), so a module's new table is
 * exported and erased without a change here. The erasure itself is {@code platform.erase_tenant}:
 * one transaction, row-level security scoped to the pharmacy, and the only way past the append-only
 * triggers.
 */
@Component
class TenantDataService implements TenantData {

  private static final Logger LOG = LoggerFactory.getLogger(TenantDataService.class);

  /** Columns that never leave: password and code hashes, tokens, provider secrets. */
  private static final Pattern SECRET =
      Pattern.compile("(?i).*(password|secret|token|_hash|credential).*");

  private static final String TENANT_TABLES =
      "select c.table_schema, c.table_name from information_schema.columns c"
          + " join pg_namespace s on s.nspname = c.table_schema"
          + " join pg_class k on k.relnamespace = s.oid and k.relname = c.table_name"
          + " where c.column_name = 'tenant_uid' and k.relkind in ('r', 'p') and not k.relispartition"
          + " order by 1, 2";

  private final JdbcTemplate jdbc;
  private final TransactionTemplate snapshot;
  private final TransactionTemplate writing;
  private final ObjectStorage storage;
  private final TenantService tenants;
  private final AuditLog audit;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Clock clock;

  TenantDataService(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      ObjectStorage storage,
      TenantService tenants,
      AuditLog audit,
      Clock clock) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setFetchSize(1_000);
    this.snapshot = new TransactionTemplate(transactions);
    this.snapshot.setReadOnly(true);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    this.writing = new TransactionTemplate(transactions);
    this.storage = storage;
    this.tenants = tenants;
    this.audit = audit;
    this.clock = clock;
  }

  // ------------------------------------------------------------------ export

  @Override
  public void export(UUID tenantUid, OutputStream out) {
    TenantView tenant = required(tenantUid);
    if (tenant.status() == TenantStatus.ERASED) {
      throw Refusal.invalid("erased", "The pharmacy's data is already erased.", "status");
    }
    Map<String, Long> rows = new LinkedHashMap<>();
    List<String> files = new ArrayList<>();
    ZipOutputStream zip = new ZipOutputStream(new KeepOpen(out), StandardCharsets.UTF_8);
    TenantContext.callAs(
        tenantUid,
        () ->
            snapshot.execute(
                status -> {
                  for (String table : tables()) {
                    rows.put(table, csv(zip, table, select(table, tenantUid)));
                  }
                  rows.put(
                      "sales.sale_line_serials",
                      csv(
                          zip,
                          "sales.sale_line_serials",
                          "select s.* from sales.sale_line_serials s join sales.sale_lines l"
                              + " on l.id = s.sale_line_id where l.tenant_uid = '"
                              + tenantUid
                              + "'"));
                  rows.put("access.users", csv(zip, "access.users", people(tenantUid)));
                  rows.put(
                      "tenancy.tenants",
                      csv(
                          zip,
                          "tenancy.tenants",
                          "select * from tenancy.tenants where uid = '" + tenantUid + "'"));
                  files.addAll(blobs(zip, tenantUid));
                  return null;
                }));
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("format", "pharmacy-export/1");
    manifest.put("tenantUid", tenantUid.toString());
    manifest.put("code", tenant.code());
    manifest.put("name", tenant.name());
    manifest.put("exportedAt", clock.instant().toString());
    manifest.put("rows", rows);
    manifest.put("files", files.size());
    manifest.put("leftOut", "password and code hashes, session and link tokens, provider secrets");
    try {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
      zip.closeEntry();
      zip.finish();
      zip.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    TenantContext.callAs(
        tenantUid,
        () -> {
          audit.record(
              "DATA_EXPORTED",
              "Tenant",
              tenantUid,
              null,
              Map.of("tables", rows.size(), "files", files.size()));
          return null;
        });
  }

  /** schema.table of every table with a tenant_uid; quoted names come from the catalogue. */
  private List<String> tables() {
    return jdbc.query(TENANT_TABLES, (rs, i) -> rs.getString(1) + "." + rs.getString(2));
  }

  private String select(String table, UUID tenantUid) {
    String[] name = table.split("\\.", 2);
    List<String> columns =
        jdbc.queryForList(
            "select column_name from information_schema.columns where table_schema = ? and"
                + " table_name = ? order by ordinal_position",
            String.class,
            name[0],
            name[1]);
    List<String> kept =
        columns.stream().filter(c -> !SECRET.matcher(c).matches()).map(c -> '"' + c + '"').toList();
    return "select "
        + String.join(", ", kept)
        + " from \""
        + name[0]
        + "\".\""
        + name[1]
        + "\" where tenant_uid = '"
        + tenantUid
        + "'";
  }

  /** The pharmacy's people: whoever holds a membership, without their password hash. */
  private String people(UUID tenantUid) {
    return "select u.uid, u.email, u.name, u.active, u.created_at from access.users u"
        + " where u.uid in (select m.user_uid from access.memberships m where m.tenant_uid = '"
        + tenantUid
        + "')";
  }

  private long csv(ZipOutputStream zip, String table, String sql) {
    try {
      zip.putNextEntry(new ZipEntry(table.replace('.', '/') + ".csv"));
      Writer writer =
          new java.io.BufferedWriter(
              new java.io.OutputStreamWriter(new KeepOpen(zip), StandardCharsets.UTF_8));
      long[] count = {0};
      boolean[] header = {false};
      jdbc.query(
          sql,
          rs -> {
            if (!header[0]) {
              writeHeader(writer, rs);
              header[0] = true;
            }
            writeRow(writer, rs);
            count[0]++;
          });
      if (!header[0]) {
        jdbc.query(sql + " limit 0", (ResultSet rs) -> writeHeaderUnchecked(writer, rs));
      }
      writer.flush();
      zip.closeEntry();
      return count[0];
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Object writeHeaderUnchecked(Writer writer, ResultSet rs) throws SQLException {
    writeHeader(writer, rs);
    return null;
  }

  private static void writeHeader(Writer writer, ResultSet rs) throws SQLException {
    int n = rs.getMetaData().getColumnCount();
    List<String> names = new ArrayList<>(n);
    for (int i = 1; i <= n; i++) {
      names.add(rs.getMetaData().getColumnLabel(i));
    }
    line(writer, names);
  }

  private static void writeRow(Writer writer, ResultSet rs) throws SQLException {
    int n = rs.getMetaData().getColumnCount();
    List<String> values = new ArrayList<>(n);
    for (int i = 1; i <= n; i++) {
      values.add(rs.getString(i));
    }
    line(writer, values);
  }

  /** RFC 4180: a field with a comma, quote or line break is quoted, its quotes doubled. */
  private static void line(Writer writer, List<String> fields) {
    try {
      for (int i = 0; i < fields.size(); i++) {
        if (i > 0) {
          writer.write(',');
        }
        String f = fields.get(i);
        if (f == null) {
          continue;
        }
        if (f.indexOf(',') >= 0
            || f.indexOf('"') >= 0
            || f.indexOf('\n') >= 0
            || f.indexOf('\r') >= 0) {
          writer.write('"');
          writer.write(f.replace("\"", "\"\""));
          writer.write('"');
        } else {
          writer.write(f);
        }
      }
      writer.write("\r\n");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private List<String> blobs(ZipOutputStream zip, UUID tenantUid) {
    List<String> written = new ArrayList<>();
    List<Map<String, Object>> rows =
        jdbc.queryForList(
            "select uid, name, storage_key from files.files where tenant_uid = ? and status ="
                + " 'COMPLETE' and storage_key is not null order by id",
            tenantUid);
    for (Map<String, Object> row : rows) {
      String key = (String) row.get("storage_key");
      String entry = "files/" + row.get("uid") + "/" + safe((String) row.get("name"));
      if (!storage.exists(key)) {
        LOG.warn("export of {}: file {} is missing from the store", tenantUid, row.get("uid"));
        continue;
      }
      try (InputStream in = storage.get(key)) {
        zip.putNextEntry(new ZipEntry(entry));
        in.transferTo(zip);
        zip.closeEntry();
        written.add(entry);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return written;
  }

  private static String safe(String name) {
    String cleaned = name == null ? "file" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    return cleaned.isBlank() || cleaned.startsWith(".") ? "file" + cleaned : cleaned;
  }

  // ------------------------------------------------------------------ erasure

  @Override
  public Map<String, Long> erase(UUID tenantUid, String confirmCode) {
    TenantView tenant = required(tenantUid);
    if (confirmCode == null || !tenant.code().equalsIgnoreCase(confirmCode.trim())) {
      throw Refusal.invalid(
          "confirm_code_mismatch", "Type the pharmacy's code to confirm.", "confirmCode");
    }
    if (tenant.status() != TenantStatus.CLOSED) {
      throw Refusal.invalid(
          "erase_refused", "Only a closed pharmacy can be erased; close it first.", "status");
    }
    if (tenant.legalHold()) {
      throw Refusal.invalid(
          "erase_refused", "The pharmacy is under a legal hold; lift it first.", "legalHold");
    }
    java.time.Instant now = clock.instant();
    if (tenant.eraseAfter() == null || tenant.eraseAfter().isAfter(now)) {
      throw Refusal.invalid(
          "erase_refused",
          "The pharmacy is in its cooling-off period until " + tenant.eraseAfter() + ".",
          "eraseAfter");
    }
    // The file keys, read before their rows go; the bytes go once the rows are gone for good.
    List<String> keys =
        TenantContext.callAs(
            tenantUid,
            () ->
                snapshot.execute(
                    status ->
                        jdbc.queryForList(
                            "select storage_key from files.files where tenant_uid = ? and"
                                + " storage_key is not null",
                            String.class,
                            tenantUid)));
    String result;
    try {
      result =
          writing.execute(
              status ->
                  jdbc.queryForObject(
                      "select platform.erase_tenant(?, ?)::text",
                      String.class,
                      tenantUid,
                      java.sql.Timestamp.from(now)));
    } catch (DataAccessException e) {
      Throwable cause = e.getMostSpecificCause();
      throw Refusal.invalid(
          "erase_refused", cause == null ? e.getMessage() : cause.getMessage(), "status");
    }
    Map<String, Long> counts =
        new LinkedHashMap<>(json.readValue(result, new TypeReference<Map<String, Long>>() {}));
    int deleted = 0;
    for (String key : keys) {
      try {
        storage.delete(key);
        deleted++;
      } catch (RuntimeException e) {
        LOG.warn(
            "erasure of {}: could not delete a file from the store: {}", tenantUid, e.toString());
      }
    }
    counts.put("files (stored)", (long) deleted);
    Map<String, Object> after = new LinkedHashMap<>(counts);
    after.put("code", tenant.code());
    audit.record("TENANT_ERASED", "Tenant", tenantUid, null, after);
    LOG.info("erased tenant {} ({}): {}", tenantUid, tenant.code(), counts);
    return counts;
  }

  private TenantView required(UUID tenantUid) {
    return tenants
        .find(tenantUid)
        .orElseThrow(() -> Refusal.notFound("tenant_not_found", "No such tenant."));
  }

  /** The servlet's stream is the caller's to close; so is the ZIP's around each CSV. */
  private static final class KeepOpen extends FilterOutputStream {
    KeepOpen(OutputStream out) {
      super(out);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      out.write(b, off, len);
    }

    @Override
    public void close() throws IOException {
      flush();
    }
  }
}
