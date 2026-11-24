package tz.co.pharmacy.delivery.api.v1.imports;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import org.dhatim.fastexcel.reader.Cell;
import org.dhatim.fastexcel.reader.CellType;
import org.dhatim.fastexcel.reader.ReadableWorkbook;
import org.dhatim.fastexcel.reader.ReadingOptions;
import org.dhatim.fastexcel.reader.Row;
import org.dhatim.fastexcel.reader.Sheet;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * An uploaded sheet: its headings (the first row) and the rows under it, each cell as the text a
 * person sees, numbered as in Excel (the headings are row 1). Reads an Excel workbook's first sheet
 * or a CSV file (UTF-8, with or without a BOM, comma or semicolon separated). Wholly empty rows are
 * skipped.
 *
 * <p>A file is read within limits, so a crafted one cannot run the API out of memory (adversarial
 * review INT-1): a workbook's ZIP is checked first ({@link ZipLimits}: parts, expanded size,
 * expansion ratio), and any file may have at most {@link #MAX_ROWS} rows, {@link #MAX_COLUMNS}
 * filled columns and {@link #MAX_CELL} characters in a cell (Excel's own limit).
 */
record SheetFile(List<String> headings, List<Line> lines) {

  /** The most rows one import takes; past it the person splits the file. */
  static final int MAX_ROWS = 5_000;

  /** The most columns read; a cell filled further right is refused (templates have about 20). */
  static final int MAX_COLUMNS = 200;

  /** The most characters in one cell: Excel's own limit. */
  static final int MAX_CELL = 32_767;

  /** One row: its number in the sheet and its cells, aligned with the headings. */
  record Line(int number, List<String> cells) {

    String cell(int column) {
      return column < 0 || column >= cells.size() ? "" : cells.get(column);
    }
  }

  static SheetFile read(String fileName, byte[] content) {
    if (content == null || content.length == 0) {
      throw Refusal.invalid("empty_file", "The file is empty.", "file");
    }
    String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
    boolean zip = content.length > 1 && content[0] == 'P' && content[1] == 'K';
    if (name.endsWith(".xls") && !zip) {
      throw Refusal.invalid(
          "unsupported_file",
          "Old .xls files cannot be read: save it as an Excel workbook (.xlsx) or CSV.",
          "file");
    }
    List<List<String>> rows = zip ? excel(content) : csv(content);
    if (rows.isEmpty()) {
      throw Refusal.invalid("empty_file", "The file has no headings row.", "file");
    }
    List<String> headings = rows.getFirst().stream().map(String::strip).toList();
    List<Line> lines = new ArrayList<>();
    for (int i = 1; i < rows.size(); i++) {
      List<String> cells = rows.get(i);
      if (cells.stream().allMatch(String::isBlank)) {
        continue;
      }
      lines.add(new Line(i + 1, cells.stream().map(String::strip).toList()));
      if (lines.size() > MAX_ROWS) {
        throw Refusal.invalid(
                "too_many_rows",
                "The file has more than " + MAX_ROWS + " rows: split it and import each part.",
                "file")
            .with("max", MAX_ROWS);
      }
    }
    return new SheetFile(headings, lines);
  }

  /** The first sheet of a workbook, row by row; a missing row number leaves an empty row. */
  private static List<List<String>> excel(byte[] content) {
    ZipLimits.check(content);
    List<List<String>> rows = new ArrayList<>();
    try (ReadableWorkbook workbook =
        new ReadableWorkbook(new ByteArrayInputStream(content), new ReadingOptions(true, false))) {
      Sheet sheet = workbook.getFirstSheet();
      try (Stream<Row> stream = sheet.openStream()) {
        for (Row row : (Iterable<Row>) stream::iterator) {
          while (rows.size() < row.getRowNum() - 1) {
            rows.add(List.of());
          }
          List<String> cells = new ArrayList<>();
          for (int c = 0; c < row.getCellCount(); c++) {
            String text = text(row.getCell(c));
            if (c >= MAX_COLUMNS) {
              if (!text.isBlank()) {
                throw tooManyColumns(row.getRowNum());
              }
              continue;
            }
            cells.add(cell(text, row.getRowNum()));
          }
          rows.add(cells);
          if (rows.size() > MAX_ROWS + 1 + 1000) {
            break; // far past the cap: stop reading, read() refuses it
          }
        }
      }
    } catch (IOException | RuntimeException failure) {
      if (failure instanceof Refusal refusal) {
        throw refusal;
      }
      throw Refusal.invalid(
          "unreadable_file", "The file could not be read as an Excel workbook.", "file");
    }
    return rows;
  }

  /** What the person sees in the cell: numbers without a trailing .0, dates as 2026-11-22. */
  private static String text(Cell cell) {
    if (cell == null) {
      return "";
    }
    CellType type = cell.getType();
    if (type == CellType.EMPTY || type == CellType.ERROR) {
      return "";
    }
    if (type == CellType.NUMBER) {
      if (isDate(cell.getDataFormatId(), cell.getDataFormatString())) {
        return cell.asDate().toLocalDate().toString();
      }
      return plain(cell.getRawValue());
    }
    if (type == CellType.BOOLEAN) {
      return Boolean.TRUE.equals(cell.asBoolean()) ? "Yes" : "No";
    }
    if (type == CellType.FORMULA) {
      String raw = cell.getRawValue();
      return raw == null ? "" : raw;
    }
    String text = cell.getText();
    return text == null ? "" : text;
  }

  /** Excel's built-in date formats are 14–22 and 45–47; custom ones show y, d or m with d. */
  private static boolean isDate(Integer id, String format) {
    if (id != null && ((id >= 14 && id <= 22) || (id >= 45 && id <= 47))) {
      return true;
    }
    if (format == null) {
      return false;
    }
    String f = format.toLowerCase(Locale.ROOT).replaceAll("\"[^\"]*\"|\\[[^]]*]", "");
    return f.contains("yy") || f.contains("dd") || (f.contains("m") && f.contains("d"));
  }

  private static String plain(String raw) {
    if (raw == null || raw.isBlank()) {
      return "";
    }
    try {
      return new BigDecimal(raw.strip()).stripTrailingZeros().toPlainString();
    } catch (NumberFormatException notNumber) {
      return raw;
    }
  }

  /** RFC 4180 CSV: quoted cells may hold separators, quotes ("") and line breaks. */
  private static List<List<String>> csv(byte[] content) {
    String text = new String(content, StandardCharsets.UTF_8);
    if (text.startsWith("﻿")) {
      text = text.substring(1);
    }
    char separator = separator(text);
    List<List<String>> rows = new ArrayList<>();
    List<String> row = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cell.append(ch);
        }
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == separator) {
        add(row, cell, rows.size() + 1);
      } else if (ch == '\n' || ch == '\r') {
        if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        add(row, cell, rows.size() + 1);
        rows.add(row);
        row = new ArrayList<>();
        if (rows.size() > MAX_ROWS + 1 + 1000) {
          return rows;
        }
      } else {
        cell.append(ch);
      }
    }
    if (!cell.isEmpty() || !row.isEmpty()) {
      add(row, cell, rows.size() + 1);
      rows.add(row);
    }
    return rows;
  }

  /** Ends a CSV cell: kept within the column and cell limits. */
  private static void add(List<String> row, StringBuilder cell, int rowNumber) {
    if (row.size() >= MAX_COLUMNS) {
      if (!cell.toString().isBlank()) {
        throw tooManyColumns(rowNumber);
      }
    } else {
      row.add(cell(cell.toString(), rowNumber));
    }
    cell.setLength(0);
  }

  private static String cell(String text, int rowNumber) {
    if (text.length() > MAX_CELL) {
      throw Refusal.invalid(
          "cell_too_long",
          "Row " + rowNumber + " has a cell longer than " + MAX_CELL + " characters.",
          "file");
    }
    return text;
  }

  private static Refusal tooManyColumns(int rowNumber) {
    return Refusal.invalid(
        "too_many_columns",
        "Row " + rowNumber + " has something past column " + MAX_COLUMNS + ": remove it.",
        "file");
  }

  /** Excel in Swahili or French locales saves CSV with semicolons; the first line tells. */
  private static char separator(String text) {
    int end = text.indexOf('\n');
    String first = end < 0 ? text : text.substring(0, end);
    long commas = first.chars().filter(ch -> ch == ',').count();
    long semicolons = first.chars().filter(ch -> ch == ';').count();
    return semicolons > commas ? ';' : ',';
  }
}
