package tz.co.pharmacy.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tz.co.pharmacy.platform.composition.LocalDatabase;
import tz.co.pharmacy.platform.persistence.ModuleMigrations;

/**
 * Migrations must be safe on a live database full of pharmacies' rows (OPS-9). The migrations run
 * once on an embedded PostgreSQL, exactly as in production (one Flyway per schema, as the owner),
 * and before each migration the database itself says which tables exist and which force row-level
 * security ({@code pg_class.relforcerowsecurity}), whether the force came from a plain statement or
 * from a loop in a {@code DO} block. Each migration's SQL is then checked against that state:
 *
 * <ul>
 *   <li><b>Backfills reach every pharmacy.</b> Migrations run as the owner, and forced row-level
 *       security applies to the owner too: an UPDATE or DELETE on a forced table, in a plain
 *       statement or inside a {@code DO} block, silently skips every pharmacy's rows (none is
 *       named). The migration lifts the force first ({@code alter table x no force row level
 *       security}) and puts it back, as platform.add_actor_uids does, or loops over the pharmacies
 *       setting {@code pharmacy.tenant_uid} for each.
 *   <li><b>No table rewrite.</b> {@code ADD COLUMN} with a volatile default ({@code
 *       gen_random_uuid()}, {@code clock_timestamp()}, ...), an identity, a serial or a stored
 *       generated column rewrites the whole table under an ACCESS EXCLUSIVE lock. A non-volatile
 *       default ({@code now()}, a constant) is only recorded in the catalogue.
 *   <li><b>No long index build on a big table.</b> {@code CREATE INDEX} without {@code
 *       CONCURRENTLY} blocks every write to the table while it builds. It fails on the big tables
 *       below and is reported (not failed) on the other existing tables; a table created in the
 *       same migration is empty and fine. {@code CREATE INDEX CONCURRENTLY} cannot run in a
 *       transaction: put it alone in its own migration, which Flyway then runs outside one (see the
 *       runbook "Migrations").
 * </ul>
 *
 * Findings in migrations that already ran in production are allowlisted below with the reason;
 * applied migrations are never edited.
 */
class MigrationSafetyTest {

  /** Tables that grow with every sale, movement, posting, message or request. */
  static final Set<String> BIG_TABLES =
      Set.of(
          "access.sessions",
          "audit.entries",
          "catalogue.price_changes",
          "finance.journal_entries",
          "finance.journal_lines",
          "integrations.deliveries",
          "inventory.batches",
          "inventory.movements",
          "inventory.stock_levels",
          "notifications.inbox",
          "notifications.messages",
          "platform.event_publication",
          "platform.idempotency_keys",
          "receivables.entries",
          "reporting.daily_product_sales",
          "sales.sale_lines",
          "sales.sale_payments",
          "sales.sales");

  /**
   * Findings in migrations already applied in production, as {@code schema/script: rule table}.
   * They ran when the tables were small (or empty); the rules hold for every new migration.
   */
  static final Map<String, String> ALLOWED =
      Map.of(
          "audit/V4__actor_uid.sql: index audit.entries",
          "applied; audit.entries is partitioned by month, and PostgreSQL cannot build an index"
              + " CONCURRENTLY on a partitioned table (create it ON ONLY the parent, build each"
              + " partition's CONCURRENTLY, then ATTACH)",
          "integrations/V4__delivery_retries.sql: index integrations.deliveries",
          "applied before any production traffic (the test environment's log was small)",
          "notifications/V5__sms_cap.sql: index notifications.messages",
          "applied before any production traffic (the test environment's log was small)");

  private static final Pattern FORCED =
      Pattern.compile("alter\\s+table\\s+([a-z_]+\\.[a-z_]+)\\s+force\\s+row\\s+level\\s+security");
  private static final Pattern LIFTED =
      Pattern.compile(
          "alter\\s+table\\s+([a-z_]+\\.[a-z_]+)\\s+no\\s+force\\s+row\\s+level\\s+security");
  private static final Pattern CHANGED =
      Pattern.compile("(?:^|;|\\s|')(update|delete\\s+from)\\s+(?:only\\s+)?([a-z_]+\\.[a-z_]+)");
  private static final Pattern DYNAMIC_CHANGE =
      Pattern.compile("(update|delete\\s+from)\\s+(?:only\\s+)?(?:%|'\\s*\\|\\||\"?\\$)");
  private static final Pattern DOLLAR =
      Pattern.compile("(?s)(\\bdo\\s+(?:language\\s+[a-z]+\\s+)?)?(\\$[a-z_0-9]*\\$)(.*?)\\2");
  private static final Pattern CREATED =
      Pattern.compile(
          "create\\s+(?:unlogged\\s+)?table\\s+(?:if\\s+not\\s+exists\\s+)?([a-z_]+\\.[a-z_]+)");
  private static final Pattern ALTER =
      Pattern.compile(
          "^\\s*alter\\s+table\\s+(?:if\\s+exists\\s+)?(?:only\\s+)?([a-z_]+\\.[a-z_]+)\\s+(.*)$",
          Pattern.DOTALL);
  private static final Pattern INDEX =
      Pattern.compile(
          "^\\s*create\\s+(?:unique\\s+)?index\\s+(concurrently\\s+)?(?:if\\s+not\\s+exists\\s+)?"
              + "(?:[a-z_0-9]+\\s+)?on\\s+(?:only\\s+)?([a-z_]+\\.[a-z_]+)",
          Pattern.DOTALL);
  private static final Pattern REWRITING_COLUMN =
      Pattern.compile(
          "\\bgenerated\\s+(?:always|by\\s+default)\\s+as\\s+identity|\\b(?:small|big)?serial\\b"
              + "|\\bgenerated\\s+always\\s+as\\s*\\(.*\\)\\s*stored");
  private static final Pattern CALL =
      Pattern.compile("([a-z_][a-z0-9_]*)(?:\\.([a-z_][a-z0-9_]*))?\\s*\\(");

  /** One migration as the database saw it just before it ran. */
  record Migration(String schema, String script, Set<String> forcedBefore, Set<String> before) {
    String name() {
      return schema + "/" + script;
    }
  }

  private static final List<Migration> MIGRATIONS = new ArrayList<>();
  private static final Set<String> FORCED_AT_END = new TreeSet<>();
  private static final Set<String> ROW_SECURITY_AT_END = new TreeSet<>();
  private static final Set<String> VOLATILE = new HashSet<>();

  @BeforeAll
  static void migrateOnce() throws IOException, SQLException {
    Map<String, String> database =
        LocalDatabase.start(Files.createTempDirectory("pharmacy-migration-safety"), freePort());
    String url = database.get("spring.datasource.url");
    ModuleMigrations migrations =
        new ModuleMigrations(
            url,
            database.get("pharmacy.database.owner-username"),
            database.get("pharmacy.database.owner-password"),
            database.get("spring.datasource.username"));
    for (String schema : ModuleMigrations.SCHEMAS) {
      migrations.configure(schema).callbacks(new Snapshot(schema)).load().migrate();
    }
    try (Connection connection =
        DriverManager.getConnection(
            url,
            database.get("pharmacy.database.owner-username"),
            database.get("pharmacy.database.owner-password"))) {
      FORCED_AT_END.addAll(tables(connection, "c.relforcerowsecurity"));
      ROW_SECURITY_AT_END.addAll(tables(connection, "c.relrowsecurity"));
      try (Statement statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "select distinct proname from pg_proc where provolatile = 'v'")) {
        while (rows.next()) {
          VOLATILE.add(rows.getString(1));
        }
      }
    }
  }

  /** Records, before each migration, the tables that exist and those that force RLS. */
  private record Snapshot(String schema) implements Callback {

    @Override
    public boolean supports(Event event, Context context) {
      return event == Event.BEFORE_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
      return true;
    }

    @Override
    public void handle(Event event, Context context) {
      try {
        MIGRATIONS.add(
            new Migration(
                schema,
                context.getMigrationInfo().getScript(),
                tables(context.getConnection(), "c.relforcerowsecurity"),
                tables(context.getConnection(), "true")));
      } catch (SQLException failure) {
        throw new IllegalStateException(failure);
      }
    }

    @Override
    public String getCallbackName() {
      return "migration-safety-snapshot";
    }
  }

  private static Set<String> tables(Connection connection, String condition) throws SQLException {
    Set<String> tables = new TreeSet<>();
    try (Statement statement = connection.createStatement();
        ResultSet rows =
            statement.executeQuery(
                "select n.nspname || '.' || c.relname from pg_class c"
                    + " join pg_namespace n on n.oid = c.relnamespace"
                    + " where c.relkind in ('r', 'p') and n.nspname not like 'pg\\_%'"
                    + " and n.nspname <> 'information_schema' and "
                    + condition)) {
      while (rows.next()) {
        tables.add(rows.getString(1));
      }
    }
    return tables;
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static String source(Migration migration) {
    String resource = "db/migration/" + migration.schema() + "/" + migration.script();
    try (InputStream in =
        MigrationSafetyTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as(resource).isNotNull();
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
  }

  /**
   * The SQL as it runs at migration time, lower case: no comments, no function bodies (they run
   * later, when called), but the bodies of {@code DO} blocks (they run now). A {@code DO} block
   * that sets {@code pharmacy.tenant_uid} per pharmacy changes rows under each one's policy: its
   * body is left out of the backfill rule ({@code perTenant}).
   */
  static String atMigrationTime(String sql, boolean keepPerTenantBlocks) {
    String text = sql.toLowerCase(Locale.ROOT);
    text = text.replaceAll("(?s)/\\*.*?\\*/", " ");
    text = text.replaceAll("--[^\\n]*", " ");
    Matcher dollar = DOLLAR.matcher(text);
    StringBuilder out = new StringBuilder();
    while (dollar.find()) {
      String body = dollar.group(3);
      boolean doBlock = dollar.group(1) != null;
      boolean perTenant = body.contains("set_config('pharmacy.tenant_uid'");
      String kept = doBlock && (keepPerTenantBlocks || !perTenant) ? " " + body + " " : " ";
      dollar.appendReplacement(out, Matcher.quoteReplacement(kept));
    }
    dollar.appendTail(out);
    return out.toString();
  }

  /** The {@code DO} block bodies of a migration (lower case, comments removed). */
  static List<String> doBlocks(String sql) {
    String text = sql.toLowerCase(Locale.ROOT);
    text = text.replaceAll("(?s)/\\*.*?\\*/", " ");
    text = text.replaceAll("--[^\\n]*", " ");
    List<String> blocks = new ArrayList<>();
    Matcher dollar = DOLLAR.matcher(text);
    while (dollar.find()) {
      if (dollar.group(1) != null) {
        blocks.add(dollar.group(3));
      }
    }
    return blocks;
  }

  /** The tables forced while this migration runs, as far as its statements can tell. */
  private static Set<String> forcedDuring(Migration migration) {
    Set<String> forced = new HashSet<>(migration.forcedBefore());
    // A later schema's table, forced in production already, that a guarded DO block reaches.
    for (String table : FORCED_AT_END) {
      if (!migration.before().contains(table)) {
        forced.add(table);
      }
    }
    return forced;
  }

  private static Set<String> createdIn(String sql) {
    Set<String> created = new HashSet<>();
    Matcher matcher = CREATED.matcher(sql);
    while (matcher.find()) {
      created.add(matcher.group(1));
    }
    return created;
  }

  private static List<String> unexpected(List<String> findings) {
    return findings.stream().filter(finding -> !ALLOWED.containsKey(finding)).toList();
  }

  @Test
  @DisplayName("every migration ran, and the database's forced tables include the loop-forced ones")
  void sawEveryMigration() {
    assertThat(MIGRATIONS).as("migrations run").hasSizeGreaterThan(90);
    // Forced in DO loops, which parsing the SQL text never saw (OPS-9).
    assertThat(FORCED_AT_END)
        .contains("insurance.claims", "reporting.daily_sales", "integrations.webhooks");
    assertThat(BIG_TABLES).allMatch(table -> MIGRATIONS.getLast().before().contains(table));
  }

  @Test
  @DisplayName(
      "a migration that updates or deletes rows of a forced-RLS table lifts the force first,"
          + " in plain SQL and in DO blocks")
  void backfillsReachEveryPharmacy() {
    List<String> problems = new ArrayList<>();
    for (Migration migration : MIGRATIONS) {
      String source = source(migration);
      String sql = atMigrationTime(source, false);
      Set<String> forced = forcedDuring(migration);
      Set<String> created = createdIn(sql);
      Matcher changed = CHANGED.matcher(sql);
      while (changed.find()) {
        String table = changed.group(2);
        if (!forced.contains(table) || created.contains(table)) {
          continue;
        }
        boolean liftedBefore = false;
        Matcher lifted = LIFTED.matcher(sql);
        while (lifted.find() && lifted.start() < changed.start()) {
          liftedBefore |= lifted.group(1).equals(table);
        }
        if (!liftedBefore) {
          problems.add(
              migration.name()
                  + ": "
                  + changed.group(1).replaceAll("\\s+", " ")
                  + " "
                  + table
                  + " without 'alter table "
                  + table
                  + " no force row level security' before it");
        }
      }
      // An UPDATE or DELETE built at run time in a DO block names its table in a variable.
      for (String block : doBlocks(source)) {
        if (block.contains("set_config('pharmacy.tenant_uid'")) {
          continue;
        }
        if (DYNAMIC_CHANGE.matcher(block).find()
            && !block.matches("(?s).*no\\s+force\\s+row\\s+level\\s+security.*")) {
          problems.add(
              migration.name()
                  + ": a DO block changes rows of a table it names at run time without lifting"
                  + " the force (or setting pharmacy.tenant_uid per pharmacy)");
        }
      }
    }
    assertThat(unexpected(problems))
        .as("backfills that would skip other pharmacies' rows")
        .isEmpty();
  }

  @Test
  @DisplayName("no migration rewrites an existing table (ADD COLUMN with a volatile default)")
  void noTableRewrites() {
    List<String> findings = new ArrayList<>();
    for (Migration migration : MIGRATIONS) {
      String sql = atMigrationTime(source(migration), true);
      Set<String> created = createdIn(sql);
      for (String statement : sql.split(";")) {
        Matcher alter = ALTER.matcher(statement);
        if (!alter.find()) {
          continue;
        }
        String table = alter.group(1);
        if (created.contains(table) || !migration.before().contains(table)) {
          continue;
        }
        for (String clause : topLevel(alter.group(2))) {
          String c = clause.trim();
          if (!c.startsWith("add ")
              || c.matches("(?s)add\\s+(constraint|primary|unique|foreign|check|exclude)\\b.*")) {
            continue;
          }
          String why = rewriteReason(c);
          if (why != null) {
            findings.add(migration.name() + ": rewrite " + table + " (" + why + ")");
          }
        }
      }
    }
    assertThat(unexpected(findings)).as("ADD COLUMN that rewrites a live table").isEmpty();
  }

  /** Why an ADD COLUMN clause rewrites the table, or null. */
  static String rewriteReason(String clauseWithStrings) {
    // Words inside string literals (a check on 'SERIAL') are not column definitions.
    String clause = clauseWithStrings.replaceAll("'(?:[^']|'')*'", "''");
    if (REWRITING_COLUMN.matcher(clause).find()) {
      return "identity, serial or stored generated column";
    }
    Matcher defaultValue =
        Pattern.compile(
                "(?s)\\bdefault\\s+(.*?)(?=\\s+(?:not\\s+null|null|check|references|constraint"
                    + "|unique|primary|collate|generated)\\b|$)")
            .matcher(clause);
    if (!defaultValue.find()) {
      return null;
    }
    Matcher call = CALL.matcher(defaultValue.group(1));
    while (call.find()) {
      String function = call.group(2) != null ? call.group(2) : call.group(1);
      if (VOLATILE.contains(function)) {
        return "volatile default " + function + "()";
      }
    }
    return null;
  }

  /** Splits an ALTER TABLE's actions on the commas outside parentheses. */
  static List<String> topLevel(String actions) {
    List<String> parts = new ArrayList<>();
    int depth = 0;
    boolean quoted = false;
    StringBuilder part = new StringBuilder();
    for (char c : actions.toCharArray()) {
      if (c == '\'') {
        quoted = !quoted;
      } else if (!quoted && c == '(') {
        depth++;
      } else if (!quoted && c == ')') {
        depth--;
      } else if (!quoted && depth == 0 && c == ',') {
        parts.add(part.toString());
        part.setLength(0);
        continue;
      }
      part.append(c);
    }
    parts.add(part.toString());
    return parts;
  }

  @Test
  @DisplayName("an index on a big existing table is built CONCURRENTLY")
  void indexesOnBigTablesAreConcurrent() {
    List<String> findings = new ArrayList<>();
    Map<String, List<String>> smaller = new LinkedHashMap<>();
    for (Migration migration : MIGRATIONS) {
      String sql = atMigrationTime(source(migration), true);
      Set<String> created = createdIn(sql);
      for (String statement : sql.split(";")) {
        Matcher index = INDEX.matcher(statement);
        if (!index.find() || index.group(1) != null) {
          continue;
        }
        String table = index.group(2);
        if (created.contains(table) || !migration.before().contains(table)) {
          continue;
        }
        if (BIG_TABLES.contains(table)) {
          findings.add(migration.name() + ": index " + table);
        } else {
          smaller.computeIfAbsent(migration.name(), name -> new ArrayList<>()).add(table);
        }
      }
    }
    // Reported, not failed: an existing smaller table is locked for writes while its index builds.
    smaller.forEach(
        (name, tables) ->
            System.out.println(
                "MigrationSafetyTest: "
                    + name
                    + " builds an index without CONCURRENTLY on "
                    + tables));
    assertThat(unexpected(findings))
        .as("CREATE INDEX without CONCURRENTLY on a big table (blocks its writes while it builds)")
        .isEmpty();
  }

  @Test
  @DisplayName("the rules catch what they are for (a synthetic migration)")
  void rulesCatchTheirCases() {
    assertThat(rewriteReason("add column ref uuid not null default gen_random_uuid()"))
        .contains("gen_random_uuid");
    assertThat(rewriteReason("add column seen timestamptz default clock_timestamp()"))
        .contains("clock_timestamp");
    assertThat(rewriteReason("add column n bigint generated always as identity")).isNotNull();
    assertThat(rewriteReason("add column at timestamptz not null default now()")).isNull();
    assertThat(rewriteReason("add column kind text default 'serial' check (kind <> 'serial')"))
        .isNull();
    String sql =
        atMigrationTime(
            """
            create function x.f() returns void language sql as $$ update x.hidden set a = 1 $$;
            do $$ begin update x.loop_forced set a = 1; end $$;
            do $$ declare t uuid; begin
              for t in select uid from tenancy.tenants loop
                perform set_config('pharmacy.tenant_uid', t::text, true);
                update x.per_tenant set a = 1;
              end loop; end $$;
            """,
            false);
    assertThat(sql).contains("update x.loop_forced").doesNotContain("x.hidden", "x.per_tenant");
    assertThat(DYNAMIC_CHANGE.matcher("execute format('update %i.%i set a = 1', s, t)").find())
        .isTrue();
    assertThat(topLevel("add column a numeric(14,2) default 0, add column b text")).hasSize(2);
  }

  @Test
  @DisplayName("every allowlisted finding still exists (the list does not rot)")
  void allowlistIsCurrent() {
    Set<String> names = new HashSet<>();
    MIGRATIONS.forEach(migration -> names.add(migration.name()));
    assertThat(ALLOWED.keySet())
        .allMatch(finding -> names.contains(finding.substring(0, finding.indexOf(':'))));
  }

  @Test
  @DisplayName("every table with row-level security forces it, the owner included")
  void rowSecurityIsForced() {
    Set<String> unforced = new TreeSet<>(ROW_SECURITY_AT_END);
    unforced.removeAll(FORCED_AT_END);
    assertThat(unforced).as("tables whose policies the owner (the migrations) skips").isEmpty();
  }

  @Test
  @DisplayName("no two migrations of a schema share a version")
  void versionsAreUnique() throws IOException {
    Map<String, Path> seen = new java.util.HashMap<>();
    List<String> problems = new ArrayList<>();
    for (Path file : files()) {
      Matcher m = Pattern.compile("^V([0-9_]+)__").matcher(file.getFileName().toString());
      if (!m.find()) {
        continue;
      }
      String key = file.getParent().getFileName() + "/V" + m.group(1);
      Path earlier = seen.putIfAbsent(key, file);
      if (earlier != null) {
        problems.add(key + ": " + earlier.getFileName() + " and " + file.getFileName());
      }
    }
    assertThat(problems).as("migration versions used twice").isEmpty();
  }

  @Test
  @DisplayName("a lifted force is put back in the same migration")
  void forceIsRestored() {
    List<String> problems = new ArrayList<>();
    for (Migration migration : MIGRATIONS) {
      String sql = atMigrationTime(source(migration), true);
      Matcher lifted = LIFTED.matcher(sql);
      while (lifted.find()) {
        String table = lifted.group(1);
        Matcher restored = FORCED.matcher(sql);
        boolean after = false;
        while (restored.find()) {
          after |= restored.group(1).equals(table) && restored.start() > lifted.start();
        }
        if (!after) {
          problems.add(migration.name() + ": " + table + " left without forced RLS");
        }
      }
    }
    assertThat(problems).as("tables left open by a migration").isEmpty();
  }

  private static List<Path> files() throws IOException {
    Path root = Path.of("..").toAbsolutePath().normalize();
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(
              path ->
                  path.toString().replace('\\', '/').contains("/src/main/resources/db/migration/"))
          .filter(path -> path.getFileName().toString().endsWith(".sql"))
          .filter(path -> !path.toString().replace('\\', '/').contains("/target"))
          .sorted(Comparator.comparing(Path::toString))
          .toList();
    }
  }
}
