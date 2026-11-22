package tz.co.pharmacy.delivery.api.v1.imports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * One row of an upload, read cell by cell. A cell that cannot be read records a problem on the row
 * (in the person's language) and reads as null, so every problem of a row is found at once.
 */
final class ImportRow {

  private static final List<DateTimeFormatter> DATES =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE,
          DateTimeFormatter.ofPattern("d/M/uuuu"),
          DateTimeFormatter.ofPattern("d-M-uuuu"),
          DateTimeFormatter.ofPattern("d.M.uuuu"));

  private final SheetFile.Line line;
  private final Map<String, Integer> positions;
  private final Map<String, ImportColumn> columns;
  private final boolean swahili;
  private final List<ImportProblem> problems = new ArrayList<>();

  ImportRow(
      SheetFile.Line line,
      Map<String, Integer> positions,
      Map<String, ImportColumn> columns,
      boolean swahili) {
    this.line = line;
    this.positions = positions;
    this.columns = columns;
    this.swahili = swahili;
  }

  int number() {
    return line.number();
  }

  List<ImportProblem> problems() {
    return problems;
  }

  boolean ok() {
    return problems.isEmpty();
  }

  /** Whether the file has the column at all; a missing column leaves the value as it is. */
  boolean has(String key) {
    return positions.containsKey(key);
  }

  /** The cell's text, or null when it is empty or the column is missing. */
  String text(String key) {
    Integer at = positions.get(key);
    if (at == null) {
      return null;
    }
    String value = line.cell(at);
    return value == null || value.isBlank() ? null : value.strip();
  }

  /**
   * The cell when the file has the column, else what the record has now ({@code was}, read with
   * {@code current}), or null for a new record: a column left out of the file changes nothing.
   */
  <V, T> T pick(String key, V was, Function<V, T> current, Function<ImportRow, T> cell) {
    if (has(key)) {
      return cell.apply(this);
    }
    return was == null ? null : current.apply(was);
  }

  /** The cell's text; a problem when it is empty. */
  String required(String key) {
    String value = text(key);
    if (value == null) {
      problem(key, "required", "is required", "inahitajika");
    }
    return value;
  }

  BigDecimal number(String key) {
    String value = text(key);
    if (value == null) {
      return null;
    }
    try {
      return new BigDecimal(value.replace(",", "").replace(" ", ""));
    } catch (NumberFormatException notNumber) {
      problem(key, "not_a_number", "is not a number: " + value, "si namba: " + value);
      return null;
    }
  }

  Integer whole(String key) {
    BigDecimal value = number(key);
    if (value == null) {
      return null;
    }
    try {
      return value.stripTrailingZeros().intValueExact();
    } catch (ArithmeticException notWhole) {
      problem(
          key,
          "not_a_whole_number",
          "is not a whole number: " + value.toPlainString(),
          "si namba kamili: " + value.toPlainString());
      return null;
    }
  }

  /** A date as 2026-12-31 or 31/12/2026 (day first, as in Tanzania). */
  LocalDate date(String key) {
    String value = text(key);
    if (value == null) {
      return null;
    }
    for (DateTimeFormatter format : DATES) {
      try {
        return LocalDate.parse(value, format);
      } catch (DateTimeParseException tryNext) {
        // the next format
      }
    }
    problem(
        key,
        "not_a_date",
        "is not a date such as 2026-12-31 or 31/12/2026: " + value,
        "si tarehe kama 2026-12-31 au 31/12/2026: " + value);
    return null;
  }

  /** Yes or No (Ndiyo or Hapana, true or false, 1 or 0). */
  Boolean yes(String key) {
    String value = text(key);
    if (value == null) {
      return null;
    }
    return switch (value.toLowerCase(Locale.ROOT)) {
      case "yes", "y", "ndiyo", "ndio", "true", "1" -> true;
      case "no", "n", "hapana", "false", "0" -> false;
      default -> {
        problem(key, "not_yes_or_no", "is not Yes or No: " + value, "si Ndiyo au Hapana: " + value);
        yield null;
      }
    };
  }

  /** One of an enum's values, written as listed or with spaces for underscores. */
  <E extends Enum<E>> E choice(String key, Class<E> type) {
    String value = text(key);
    if (value == null) {
      return null;
    }
    String name = value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException unknown) {
      String allowed = ImportColumn.values(type);
      problem(
          key,
          "not_allowed",
          "is not one of " + allowed + ": " + value,
          "si mojawapo ya " + allowed + ": " + value);
      return null;
    }
  }

  void problem(String key, String reason, String en, String sw) {
    ImportColumn column = columns.get(key);
    String heading = column == null ? null : column.heading(swahili);
    problems.add(
        new ImportProblem(
            line.number(),
            heading,
            reason,
            heading == null ? (swahili ? sw : en) : heading + " " + (swahili ? sw : en)));
  }
}
