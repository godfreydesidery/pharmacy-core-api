package tz.co.pharmacy.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Migrations that change existing rows must reach every pharmacy's rows. Migrations run as each
 * schema's owner, and row-level security is forced on the owner too: an UPDATE or DELETE on a
 * forced table silently skips other pharmacies' rows (no pharmacy is named), which fails only on
 * real data, at deploy. A migration must lift the force for that table first ({@code alter table x
 * no force row level security}) and put it back, as platform.add_actor_uids does.
 */
class MigrationSafetyTest {

  private static final Pattern FORCED =
      Pattern.compile("alter\\s+table\\s+([a-z_]+\\.[a-z_]+)\\s+force\\s+row\\s+level\\s+security");
  private static final Pattern LIFTED =
      Pattern.compile(
          "alter\\s+table\\s+([a-z_]+\\.[a-z_]+)\\s+no\\s+force\\s+row\\s+level\\s+security");
  private static final Pattern CHANGED =
      Pattern.compile("(?:^|;|\\s)(update|delete\\s+from)\\s+([a-z_]+\\.[a-z_]+)");

  private static List<Path> migrations() throws IOException {
    Path root = Path.of("..").toAbsolutePath().normalize();
    try (Stream<Path> files = Files.walk(root)) {
      return files
          .filter(
              path ->
                  path.toString().replace('\\', '/').contains("/src/main/resources/db/migration/"))
          .filter(path -> path.getFileName().toString().endsWith(".sql"))
          .filter(path -> !path.toString().replace('\\', '/').contains("/target"))
          .sorted(Comparator.comparing(MigrationSafetyTest::version))
          .toList();
    }
  }

  /** Orders V9 before V10, V4_1 after V4. */
  private static String version(Path path) {
    String name = path.getFileName().toString();
    Matcher m = Pattern.compile("^V(\\d+)(?:_(\\d+))?__").matcher(name);
    return m.find()
        ? "%05d.%05d %s"
            .formatted(
                Integer.parseInt(m.group(1)),
                m.group(2) == null ? 0 : Integer.parseInt(m.group(2)),
                path)
        : name;
  }

  /** The SQL as it runs at migration time: no comments, no function bodies (they run later). */
  private static String statements(Path path) throws IOException {
    String sql = Files.readString(path).toLowerCase(Locale.ROOT);
    sql = sql.replaceAll("(?s)\\$\\$.*?\\$\\$", " ");
    sql = sql.replaceAll("--[^\\n]*", " ");
    sql = sql.replaceAll("(?s)/\\*.*?\\*/", " ");
    return sql;
  }

  @Test
  @DisplayName(
      "a migration that updates or deletes rows of a forced-RLS table lifts the force first")
  void backfillsReachEveryPharmacy() throws IOException {
    Set<String> forced = new HashSet<>();
    List<String> problems = new ArrayList<>();
    List<Path> files = migrations();
    assertThat(files).as("migrations found").hasSizeGreaterThan(20);
    for (Path file : files) {
      String sql = statements(file);
      Matcher changed = CHANGED.matcher(sql);
      while (changed.find()) {
        String table = changed.group(2);
        if (!forced.contains(table)) {
          continue;
        }
        int at = changed.start();
        Matcher lifted = LIFTED.matcher(sql);
        boolean liftedBefore = false;
        while (lifted.find() && lifted.start() < at) {
          if (lifted.group(1).equals(table)) {
            liftedBefore = true;
          }
        }
        if (!liftedBefore) {
          problems.add(
              file.getFileName()
                  + ": "
                  + changed.group(1).replaceAll("\\s+", " ")
                  + " "
                  + table
                  + " without 'alter table "
                  + table
                  + " no force row level security' before it");
        }
      }
      Matcher force = FORCED.matcher(sql);
      while (force.find()) {
        forced.add(force.group(1));
      }
    }
    assertThat(problems).as("backfills that would skip other pharmacies' rows").isEmpty();
  }

  @Test
  @DisplayName("a lifted force is put back in the same migration")
  void forceIsRestored() throws IOException {
    List<String> problems = new ArrayList<>();
    for (Path file : migrations()) {
      String sql = statements(file);
      Matcher lifted = LIFTED.matcher(sql);
      while (lifted.find()) {
        String table = lifted.group(1);
        Matcher restored = FORCED.matcher(sql);
        boolean after = false;
        while (restored.find()) {
          if (restored.group(1).equals(table) && restored.start() > lifted.start()) {
            after = true;
          }
        }
        if (!after) {
          problems.add(file.getFileName() + ": " + table + " left without forced RLS");
        }
      }
    }
    assertThat(problems).as("tables left open by a migration").isEmpty();
  }
}
