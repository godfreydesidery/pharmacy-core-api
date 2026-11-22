package tz.co.pharmacy.delivery.api.v1.imports;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Entitlements;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Runs an import in three steps, writing nothing until the whole file is clean:
 *
 * <ol>
 *   <li>the headings: every column known, every required one present;
 *   <li>every row read (numbers, dates, allowed values, lookups) and the file's own duplicates;
 *   <li>the rules: a check tries each row against the real rules in a transaction that is always
 *       rolled back; an import saves every row in one transaction, or none when one is refused.
 * </ol>
 */
@Component
class ImportEngine {

  private static final Logger log = LoggerFactory.getLogger(ImportEngine.class);

  private final TransactionTemplate tx;
  private final Entitlements entitlements;

  ImportEngine(PlatformTransactionManager transactions, Entitlements entitlements) {
    this.tx = new TransactionTemplate(transactions);
    this.entitlements = entitlements;
  }

  /** Every problem of the file; nothing is written. */
  <P> ImportReport check(Importer<P> importer, SheetFile sheet, boolean swahili) {
    return run(importer, sheet, swahili, false);
  }

  /** Writes every row in one transaction, or nothing when the file has a problem. */
  <P> ImportReport save(Importer<P> importer, SheetFile sheet, boolean swahili) {
    return run(importer, sheet, swahili, true);
  }

  private record Planned<P>(int row, P plan) {}

  private <P> ImportReport run(
      Importer<P> importer, SheetFile sheet, boolean swahili, boolean save) {
    String kind = importer.kind();
    int rows = sheet.lines().size();
    List<ImportProblem> problems = new ArrayList<>();
    Map<String, ImportColumn> columns = new LinkedHashMap<>();
    importer.columns().forEach(column -> columns.put(column.key(), column));
    Map<String, Integer> positions = headings(sheet, columns, swahili, problems);
    if (!problems.isEmpty()) {
      return ImportReport.of(kind, rows, 0, 0, false, problems);
    }
    if (rows == 0) {
      problems.add(
          new ImportProblem(
              null,
              null,
              "no_rows",
              swahili
                  ? "Faili halina safu chini ya vichwa."
                  : "The file has no rows under the headings."));
      return ImportReport.of(kind, rows, 0, 0, false, problems);
    }

    Importer.Batch<P> batch = importer.start();
    List<Planned<P>> planned = new ArrayList<>();
    for (SheetFile.Line line : sheet.lines()) {
      ImportRow row = new ImportRow(line, positions, columns, swahili);
      P plan = null;
      try {
        plan = batch.read(row);
      } catch (Refusal refusal) {
        row.problems().add(problem(line.number(), refusal, columns, swahili));
      }
      problems.addAll(row.problems());
      if (row.ok() && plan != null) {
        planned.add(new Planned<>(line.number(), plan));
      }
    }
    duplicates(batch, planned, columns, swahili, problems);

    int creating = (int) planned.stream().filter(p -> batch.creates(p.plan())).count();
    int updating = planned.size() - creating;
    if (importer.limit() != null && !ExecutionContext.rootAdmin()) {
      try {
        entitlements.requireRoom(ExecutionContext.requireTenant(), importer.limit(), creating);
      } catch (Refusal refusal) {
        problems.add(new ImportProblem(null, null, refusal.reason(), refusal.getMessage()));
      }
    }
    if (!problems.isEmpty()) {
      return ImportReport.of(kind, rows, creating, updating, false, problems);
    }
    if (!save) {
      trial(batch, planned, columns, swahili, problems);
      return ImportReport.of(kind, rows, creating, updating, false, problems);
    }
    ImportProblem failure = saveAll(batch, planned, columns, swahili);
    if (failure != null) {
      return ImportReport.of(kind, rows, creating, updating, false, List.of(failure));
    }
    return ImportReport.of(kind, rows, creating, updating, true, List.of());
  }

  /** Where each known column is; unknown, repeated and missing required columns are problems. */
  private static Map<String, Integer> headings(
      SheetFile sheet,
      Map<String, ImportColumn> columns,
      boolean swahili,
      List<ImportProblem> problems) {
    Map<String, Integer> positions = new HashMap<>();
    List<String> headings = sheet.headings();
    for (int i = 0; i < headings.size(); i++) {
      String heading = headings.get(i);
      if (heading.isBlank()) {
        continue;
      }
      ImportColumn column =
          columns.values().stream().filter(c -> c.matches(heading)).findFirst().orElse(null);
      if (column == null) {
        problems.add(
            new ImportProblem(
                1,
                heading,
                "unknown_column",
                swahili
                    ? "Safu \"" + heading + "\" haijulikani: iondoe au tumia vichwa vya kiolezo."
                    : "Unknown column \""
                        + heading
                        + "\": remove it or use the headings of the template."));
      } else if (positions.putIfAbsent(column.key(), i) != null) {
        problems.add(
            new ImportProblem(
                1,
                heading,
                "repeated_column",
                swahili
                    ? "Safu \"" + heading + "\" imerudiwa."
                    : "The column \"" + heading + "\" appears twice."));
      }
    }
    for (ImportColumn column : columns.values()) {
      if (column.required() && !positions.containsKey(column.key())) {
        problems.add(
            new ImportProblem(
                1,
                column.heading(swahili),
                "missing_column",
                swahili
                    ? "Safu ya lazima \"" + column.heading(true) + "\" haipo."
                    : "The required column \"" + column.heading(false) + "\" is missing."));
      }
    }
    return positions;
  }

  /** A value that must be unique in the file and is on an earlier row too. */
  private static <P> void duplicates(
      Importer.Batch<P> batch,
      List<Planned<P>> planned,
      Map<String, ImportColumn> columns,
      boolean swahili,
      List<ImportProblem> problems) {
    Map<String, Integer> seen = new HashMap<>();
    for (Planned<P> p : planned) {
      for (Importer.Key key : batch.keys(p.plan())) {
        if (key.value() == null || key.value().isBlank()) {
          continue;
        }
        String id = key.column() + "\u0000" + key.value().strip().toLowerCase(Locale.ROOT);
        Integer first = seen.putIfAbsent(id, p.row());
        if (first != null) {
          ImportColumn column = columns.get(key.column());
          String heading = column == null ? null : column.heading(swahili);
          problems.add(
              new ImportProblem(
                  p.row(),
                  heading,
                  "repeated_in_file",
                  swahili
                      ? heading + " " + key.value() + " iko pia kwenye safu " + first + "."
                      : heading + " " + key.value() + " is also on row " + first + "."));
        }
      }
    }
  }

  /**
   * Tries the rows against the rules without keeping anything: in order, in a transaction that is
   * always rolled back. A refused row is noted and the trial goes on from the next row in a fresh
   * transaction, so a file with many problems takes one transaction per problem, not per row.
   */
  private <P> void trial(
      Importer.Batch<P> batch,
      List<Planned<P>> planned,
      Map<String, ImportColumn> columns,
      boolean swahili,
      List<ImportProblem> problems) {
    int from = 0;
    while (from < planned.size()) {
      int start = from;
      int[] stoppedAt = {-1};
      try {
        tx.executeWithoutResult(
            status -> {
              status.setRollbackOnly();
              for (int i = start; i < planned.size(); i++) {
                Planned<P> p = planned.get(i);
                try {
                  batch.save(p.plan());
                } catch (RuntimeException refused) {
                  problems.add(problem(p.row(), refused, columns, swahili));
                  stoppedAt[0] = i;
                  return;
                }
              }
              try {
                batch.finish();
              } catch (RuntimeException refused) {
                problems.add(problem(null, refused, columns, swahili));
              }
            });
      } catch (RuntimeException failure) {
        // The rollback itself failed; report it on the file and stop trying.
        log.warn("import trial of {} rows failed", planned.size(), failure);
        problems.add(problem(null, failure, columns, swahili));
        return;
      }
      from = stoppedAt[0] < 0 ? planned.size() : stoppedAt[0] + 1;
    }
  }

  /** Every row in one transaction; the first refusal rolls them all back and is returned. */
  private <P> ImportProblem saveAll(
      Importer.Batch<P> batch,
      List<Planned<P>> planned,
      Map<String, ImportColumn> columns,
      boolean swahili) {
    try {
      return tx.execute(
          status -> {
            for (Planned<P> p : planned) {
              try {
                batch.save(p.plan());
              } catch (RuntimeException refused) {
                status.setRollbackOnly();
                return problem(p.row(), refused, columns, swahili);
              }
            }
            try {
              batch.finish();
            } catch (RuntimeException refused) {
              status.setRollbackOnly();
              return problem(null, refused, columns, swahili);
            }
            return null;
          });
    } catch (RuntimeException failure) {
      log.warn("import of {} rows failed at commit", planned.size(), failure);
      return problem(null, failure, columns, swahili);
    }
  }

  /** A refusal as a problem on its row, in the column its field belongs to. */
  private static ImportProblem problem(
      Integer row, RuntimeException failure, Map<String, ImportColumn> columns, boolean swahili) {
    if (failure instanceof Refusal refusal) {
      String field = refusal.field();
      String heading =
          field == null
              ? null
              : columns.values().stream()
                  .filter(c -> c.fields().contains(field))
                  .map(c -> c.heading(swahili))
                  .findFirst()
                  .orElse(null);
      return new ImportProblem(row, heading, refusal.reason(), refusal.getMessage());
    }
    log.warn("import row {} failed", row, failure);
    return new ImportProblem(
        row,
        null,
        "not_saved",
        swahili
            ? "Safu hii haikuweza kuhifadhiwa; jaribu tena au wasiliana na msaada."
            : "This row could not be saved; try again or contact support.");
  }
}
