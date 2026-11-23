package tz.co.pharmacy.platform.composition;

import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.modules.files.ports.inbound.Archives;
import tz.co.pharmacy.modules.files.ports.inbound.FileDeletions;
import tz.co.pharmacy.modules.files.ports.outbound.ObjectStorage;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantData;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.CellText;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;
import tz.co.pharmacy.platform.persistence.exports.DataExportJobs;

/**
 * Export and erasure of one pharmacy, across every module's tables and the file store. The tables
 * are found from the catalogue (every table with a {@code tenant_uid}), so a module's new table is
 * exported and erased without a change here. The erasure itself is {@code platform.erase_tenant}:
 * one transaction, row-level security scoped to the pharmacy, and the only way past the append-only
 * triggers.
 *
 * <p>The export is read in pages, each page its own short read-only transaction (keyset on the
 * table's primary key), and written to a temporary file; the database is never held while a client
 * downloads (adversarial review INT-7, PERF-12). It is not one snapshot: a row written while the
 * export runs may or may not be in it. A consistent snapshot would need one transaction open for
 * the whole export (an exported snapshot, {@code pg_export_snapshot}, is only valid while the
 * transaction that exported it stays open), which is what held a connection and the vacuum horizon
 * for minutes; a pharmacy taking its data out is usually closed or read-only by then, and each page
 * still sees whole committed rows. The manifest says so.
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

  private static final String PRIMARY_KEY =
      "select a.attname, format_type(a.atttypid, a.atttypmod) from pg_index i"
          + " join pg_attribute a on a.attrelid = i.indrelid and a.attnum = any(i.indkey)"
          + " where i.indrelid = (quote_ident(?) || '.' || quote_ident(?))::regclass"
          + " and i.indisprimary order by array_position(i.indkey::int2[], a.attnum)";

  /**
   * The pharmacy's own record, by explicit columns: root's internal notes (the status reason, the
   * legal hold and its reason) and who at the platform created it stay out (INT-17).
   */
  private static final String TENANT_ROW =
      "select uid, code, name, legal_name, country, currency, timezone, status, suspension_kind,"
          + " contact_name, contact_email, contact_phone, address, created_at, closed_at,"
          + " erase_after from tenancy.tenants where uid = ?";

  /**
   * The pharmacy's people: whoever holds or held a membership. Someone who has left appears as they
   * were when they left (the membership's {@code former_*} snapshot), never with their current
   * account details (ISO-6).
   */
  private static final String PEOPLE =
      "select u.uid,"
          + " case when m.active then u.name else m.former_name end as name,"
          + " case when m.active then u.email else m.former_email end as email,"
          + " case when m.active then u.phone else m.former_phone end as phone,"
          + " m.active, m.created_at as joined_at, m.last_active_at"
          + " from access.memberships m join access.users u on u.uid = m.user_uid"
          + " where m.tenant_uid = ? order by m.id";

  private static final Set<String> MESSAGE_TEXT = Set.of("body", "html_body", "subject");

  private static final Set<Integer> NUMBERS =
      Set.of(
          Types.BIGINT,
          Types.INTEGER,
          Types.SMALLINT,
          Types.TINYINT,
          Types.NUMERIC,
          Types.DECIMAL,
          Types.DOUBLE,
          Types.FLOAT,
          Types.REAL);

  /** A heartbeat at most this often while an export is built (well inside the stale period). */
  private static final long BEAT_NANOS = 30_000_000_000L;

  private final JdbcTemplate jdbc;
  private final TransactionTemplate reading;
  private final TransactionTemplate writing;
  private final ObjectStorage storage;
  private final FileDeletions deletions;
  private final TenantService tenants;
  private final DataExportJobs jobs;
  private final AuditLog audit;
  private final JsonMapper json = JsonMapper.builder().build();
  private final Clock clock;
  private final int pageRows;

  TenantDataService(
      DataSource dataSource,
      PlatformTransactionManager transactions,
      ObjectStorage storage,
      FileDeletions deletions,
      TenantService tenants,
      DataExportJobs jobs,
      AuditLog audit,
      Clock clock,
      @Value("${pharmacy.exports.page-rows:5000}") int pageRows) {
    this.jdbc = new JdbcTemplate(dataSource);
    this.jdbc.setFetchSize(1_000);
    this.reading = new TransactionTemplate(transactions);
    this.reading.setReadOnly(true);
    this.reading.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    this.writing = new TransactionTemplate(transactions);
    this.storage = storage;
    this.deletions = deletions;
    this.tenants = tenants;
    this.jobs = jobs;
    this.audit = audit;
    this.clock = clock;
    this.pageRows = Math.max(1, pageRows);
  }

  // ------------------------------------------------------------------ export

  /** What one export wrote. */
  record Written(Map<String, Long> rows, int files) {}

  /**
   * The synchronous download (the old {@code GET /data-export} and the root console's): built into
   * a temporary file as a STREAMED job, which holds the pharmacy's one export slot (409
   * export_running), then copied to {@code out}.
   */
  @Override
  public void export(UUID tenantUid, OutputStream out) {
    TenantView tenant = exportable(tenantUid);
    DataExportJobs.Job job = jobs.start(tenantUid, DataExportJobs.Mode.STREAMED);
    Path temp = null;
    try {
      temp = Files.createTempFile("pharmacy-export-", ".zip");
      Written written;
      try (OutputStream file = Files.newOutputStream(temp)) {
        written = write(tenant, file, () -> jobs.heartbeat(tenantUid, job.uid()));
      }
      jobs.delivered(tenantUid, job.uid(), Files.size(temp));
      audited(tenantUid, job.uid(), written);
      Files.copy(temp, out);
    } catch (IOException e) {
      jobs.fail(tenantUid, job.uid(), e.toString());
      throw new UncheckedIOException(e);
    } catch (RuntimeException e) {
      jobs.fail(tenantUid, job.uid(), e.toString());
      throw e;
    } finally {
      deleteQuietly(temp);
    }
  }

  /** The pharmacy, if its data may be exported: 404 tenant_not_found, 422 erased. */
  TenantView exportable(UUID tenantUid) {
    TenantView tenant = required(tenantUid);
    if (tenant.status() == TenantStatus.ERASED) {
      throw Refusal.invalid("erased", "The pharmacy's data is already erased.", "status");
    }
    return tenant;
  }

  /** Records the export in the pharmacy's audit trail, as the current actor. */
  void audited(UUID tenantUid, UUID jobUid, Written written) {
    TenantContext.callAs(
        tenantUid,
        () -> {
          writing.executeWithoutResult(
              status ->
                  audit.record(
                      "DATA_EXPORTED",
                      "Tenant",
                      tenantUid,
                      null,
                      Map.of(
                          "job",
                          jobUid.toString(),
                          "tables",
                          written.rows().size(),
                          "files",
                          written.files())));
          return null;
        });
  }

  /**
   * Writes the pharmacy's export to {@code out} as a ZIP: a CSV per table, its people, its own
   * record, its files and {@code manifest.json}. {@code heartbeat} runs now and then while it is at
   * it (a worker's sign of life).
   */
  Written write(TenantView tenant, OutputStream out, Runnable heartbeat) {
    UUID tenantUid = tenant.uid();
    Beat beat = new Beat(heartbeat);
    Instant started = clock.instant();
    Map<String, Long> rows = new LinkedHashMap<>();
    ZipOutputStream zip = new ZipOutputStream(new KeepOpen(out), StandardCharsets.UTF_8);
    for (String table : read(tenantUid, this::tables)) {
      rows.put(table, csv(zip, table, tableQuery(tenantUid, table), tenantUid, beat));
    }
    rows.put(
        "sales.sale_line_serials",
        csv(
            zip,
            "sales.sale_line_serials",
            new Query(
                "s.*",
                "sales.sale_line_serials s join sales.sale_lines l on l.id = s.sale_line_id"
                    + " where l.tenant_uid = ?",
                keys("sales", "sale_line_serials", "s.")),
            tenantUid,
            beat));
    rows.put("access.users", csv(zip, "access.users", Query.once(PEOPLE), tenantUid, beat));
    rows.put(
        "tenancy.tenants", csv(zip, "tenancy.tenants", Query.once(TENANT_ROW), tenantUid, beat));
    List<String> files = blobs(zip, tenantUid, beat);
    Map<String, Object> manifest = new LinkedHashMap<>();
    manifest.put("format", "pharmacy-export/1");
    manifest.put("tenantUid", tenantUid.toString());
    manifest.put("code", tenant.code());
    manifest.put("name", tenant.name());
    manifest.put("startedAt", started.toString());
    manifest.put("exportedAt", clock.instant().toString());
    manifest.put("rows", rows);
    manifest.put("files", files.size());
    manifest.put(
        "leftOut",
        "password and code hashes, session and link tokens, provider secrets, the platform's"
            + " internal notes on the pharmacy");
    manifest.put(
        "consistency",
        "each table is read in pages between startedAt and exportedAt; a row written meanwhile"
            + " may or may not be included");
    manifest.put(
        "textCells",
        "text beginning with = + - @ a tab or a carriage return starts with ' (so a spreadsheet"
            + " does not run it as a formula); numbers are written as they are");
    try {
      zip.putNextEntry(new ZipEntry("manifest.json"));
      zip.write(json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));
      zip.closeEntry();
      zip.finish();
      zip.flush();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return new Written(rows, files.size());
  }

  /**
   * A message's text leaves masked: an account message (a link or a code that opens an account) not
   * at all, any other with the secret values of its links replaced (adversarial review ISO-3).
   */
  private static String maskedText(String column) {
    return "case when category = 'access' and '"
        + column
        + "' <> 'subject' then null else regexp_replace(\""
        + column
        // No question mark in this SQL: the statement is prepared, where ? is a parameter.
        + "\", '(\\W(token|code|key|secret|signature|sig)=)[^&[:space:]\"''<>]+', '\\1***',"
        + " 'gi') end as \""
        + column
        + "\"";
  }

  /** schema.table of every table with a tenant_uid; quoted names come from the catalogue. */
  private List<String> tables() {
    return jdbc.query(TENANT_TABLES, (rs, i) -> rs.getString(1) + "." + rs.getString(2));
  }

  private Query tableQuery(UUID tenantUid, String table) {
    String[] name = table.split("\\.", 2);
    return read(
        tenantUid,
        () -> {
          List<String> columns =
              jdbc.queryForList(
                  "select column_name from information_schema.columns where table_schema = ? and"
                      + " table_name = ? order by ordinal_position",
                  String.class,
                  name[0],
                  name[1]);
          boolean messages = "notifications".equals(name[0]) && "messages".equals(name[1]);
          List<String> kept =
              columns.stream()
                  .filter(c -> !SECRET.matcher(c).matches())
                  .map(c -> messages && MESSAGE_TEXT.contains(c) ? maskedText(c) : '"' + c + '"')
                  .toList();
          return new Query(
              String.join(", ", kept),
              "\"" + name[0] + "\".\"" + name[1] + "\" where tenant_uid = ?",
              keys(name[0], name[1], ""));
        });
  }

  /** The table's primary key columns (prefixed for the query), with their types. */
  private List<Key> keys(String schema, String table, String prefix) {
    return jdbc.query(
        PRIMARY_KEY,
        (rs, i) -> new Key(prefix + '"' + rs.getString(1) + '"', rs.getString(2)),
        schema,
        table);
  }

  /** A table read: its select list, its from-and-where (one ? for the pharmacy) and its key. */
  private record Query(String columns, String from, List<Key> keys) {

    /** A small read in one go (no paging). */
    static Query once(String sql) {
      return new Query(null, sql, List.of());
    }
  }

  private record Key(String column, String type) {}

  /** One page read: how many rows, and the last row's key. */
  private record Chunk(int rows, List<String> last) {}

  /**
   * Writes one CSV entry, page by page: each page is its own short transaction, the next one
   * starting after the last key of the previous one (keyset paging, so a long table is never read
   * in one transaction and no row is read twice).
   */
  private long csv(ZipOutputStream zip, String table, Query query, UUID tenantUid, Beat beat) {
    try {
      zip.putNextEntry(new ZipEntry(table.replace('.', '/') + ".csv"));
      Writer writer =
          new BufferedWriter(new OutputStreamWriter(new KeepOpen(zip), StandardCharsets.UTF_8));
      boolean[] header = {false};
      long count = 0;
      List<String> after = null;
      while (true) {
        List<String> from = after;
        String sql = sql(query, from);
        int width = query.keys().size();
        Chunk chunk =
            read(
                tenantUid,
                () ->
                    jdbc.query(
                        sql,
                        statement -> {
                          statement.setObject(1, tenantUid);
                          if (from != null) {
                            for (int k = 0; k < from.size(); k++) {
                              statement.setString(k + 2, from.get(k));
                            }
                          }
                        },
                        (ResultSetExtractor<Chunk>) rs -> page(rs, writer, header, width)));
        count += chunk.rows();
        beat.maybe();
        if (query.keys().isEmpty() || chunk.rows() < pageRows || chunk.last() == null) {
          break;
        }
        after = chunk.last();
      }
      writer.flush();
      zip.closeEntry();
      return count;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private String sql(Query query, List<String> after) {
    if (query.columns() == null) {
      return query.from();
    }
    if (query.keys().isEmpty()) {
      return "select " + query.columns() + " from " + query.from();
    }
    List<String> keyColumns = query.keys().stream().map(Key::column).toList();
    StringBuilder sql = new StringBuilder("select ").append(query.columns());
    for (int k = 0; k < keyColumns.size(); k++) {
      sql.append(", ").append(keyColumns.get(k)).append("::text as \"__key").append(k).append('"');
    }
    sql.append(" from ").append(query.from());
    if (after != null) {
      sql.append(" and (").append(String.join(", ", keyColumns)).append(") > (");
      for (int k = 0; k < query.keys().size(); k++) {
        sql.append(k > 0 ? ", " : "").append("?::").append(query.keys().get(k).type());
      }
      sql.append(')');
    }
    sql.append(" order by ").append(String.join(", ", keyColumns));
    sql.append(" limit ").append(pageRows);
    return sql.toString();
  }

  /** Writes a page's rows (and the header, once); the last {@code keys} columns are its key. */
  private static Chunk page(ResultSet rs, Writer writer, boolean[] header, int keys)
      throws SQLException {
    ResultSetMetaData meta = rs.getMetaData();
    int width = meta.getColumnCount() - keys;
    if (!header[0]) {
      List<String> names = new ArrayList<>(width);
      for (int i = 1; i <= width; i++) {
        names.add(meta.getColumnLabel(i));
      }
      line(writer, names);
      header[0] = true;
    }
    int rows = 0;
    List<String> last = null;
    while (rs.next()) {
      List<String> values = new ArrayList<>(width);
      for (int i = 1; i <= width; i++) {
        String value = rs.getString(i);
        values.add(NUMBERS.contains(meta.getColumnType(i)) ? value : CellText.neutralised(value));
      }
      line(writer, values);
      if (keys > 0) {
        last = new ArrayList<>(keys);
        for (int k = 1; k <= keys; k++) {
          last.add(rs.getString(width + k));
        }
      }
      rows++;
    }
    return new Chunk(rows, last);
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

  /**
   * The pharmacy's stored files (not its archives, such as earlier exports), read from the store
   * one by one outside any transaction.
   */
  private List<String> blobs(ZipOutputStream zip, UUID tenantUid, Beat beat) {
    List<String> written = new ArrayList<>();
    List<Map<String, Object>> rows =
        read(
            tenantUid,
            () ->
                jdbc.queryForList(
                    "select uid, name, storage_key from files.files where tenant_uid = ? and"
                        + " status = 'COMPLETE' and storage_key is not null and owner_type <> ?"
                        + " order by id",
                    tenantUid,
                    Archives.OWNER_TYPE));
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
      beat.maybe();
    }
    return written;
  }

  private static String safe(String name) {
    String cleaned = name == null ? "file" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
    return cleaned.isBlank() || cleaned.startsWith(".") ? "file" + cleaned : cleaned;
  }

  /** One short read-only transaction as the pharmacy. */
  private <T> T read(UUID tenantUid, Supplier<T> work) {
    return TenantContext.callAs(tenantUid, () -> reading.execute(status -> work.get()));
  }

  /** Runs the heartbeat at most every {@link #BEAT_NANOS}. */
  private static final class Beat {
    private final Runnable heartbeat;
    private long last = System.nanoTime();

    Beat(Runnable heartbeat) {
      this.heartbeat = heartbeat;
    }

    void maybe() {
      long now = System.nanoTime();
      if (now - last >= BEAT_NANOS) {
        last = now;
        heartbeat.run();
      }
    }
  }

  private static void deleteQuietly(Path path) {
    if (path == null) {
      return;
    }
    try {
      Files.deleteIfExists(path);
    } catch (IOException e) {
      LOG.warn("could not delete the temporary export {}: {}", path, e.toString());
    }
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
    Instant now = clock.instant();
    if (tenant.eraseAfter() == null || tenant.eraseAfter().isAfter(now)) {
      throw Refusal.invalid(
          "erase_refused",
          "The pharmacy is in its cooling-off period until " + tenant.eraseAfter() + ".",
          "eraseAfter");
    }
    if (jobs.open(tenantUid)) {
      throw Refusal.invalid(
          "erase_refused",
          "An export of the pharmacy is being prepared; erase it once the export is finished.",
          "status");
    }
    // The file keys, read before their rows go; the bytes go once the rows are gone for good.
    List<String> keys =
        read(
            tenantUid,
            () ->
                jdbc.queryForList(
                    "select storage_key from files.files where tenant_uid = ? and"
                        + " storage_key is not null",
                    String.class,
                    tenantUid));
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
    long deleted = 0;
    long queued = 0;
    for (String key : keys) {
      if (deletions.delete(key, "erasure of " + tenantUid)) {
        deleted++;
      } else {
        queued++;
      }
    }
    counts.put("files (stored)", deleted);
    counts.put("files (queued for a retry)", queued);
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

  /** The caller's stream is the caller's to close; so is the ZIP's around each CSV. */
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
