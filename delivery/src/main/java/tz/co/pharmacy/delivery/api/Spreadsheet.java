package tz.co.pharmacy.delivery.api;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * A list route's export: every row its filters match (not only the page on screen), as an Excel
 * workbook ({@code format=xlsx}, the default) or CSV, with column headings in English or Swahili
 * ({@code lang=sw}). The rows are read inside the request, where the pharmacy is known, and the
 * file is answered whole.
 */
public final class Spreadsheet {

  /** The most rows one export holds; past it the person narrows the filters. */
  public static final int MAX_ROWS = 50_000;

  private static final int PAGE = 100;

  private Spreadsheet() {}

  /** One column: its heading in English and Swahili, and its value in a row. */
  public record Column<T>(String en, String sw, Function<T, Object> value) {}

  public static <T> Column<T> column(String en, String sw, Function<T, Object> value) {
    return new Column<>(en, sw, value);
  }

  /** Every row of a numbered list, page by page. 422 too_many_rows past {@link #MAX_ROWS}. */
  public static <T> List<T> all(Function<PageQuery, Page<T>> list) {
    List<T> rows = new ArrayList<>();
    for (int page = 1; ; page++) {
      Page<T> next = list.apply(PageQuery.of(null, PAGE, page));
      rows.addAll(next.items());
      if (rows.size() > MAX_ROWS) {
        throw Refusal.invalid(
            "too_many_rows",
            "More than " + MAX_ROWS + " rows match: narrow the filters and export again.",
            "format");
      }
      long total = next.total() == null ? rows.size() : next.total();
      if (next.items().size() < PAGE || rows.size() >= total) {
        return rows;
      }
    }
  }

  /** The file: {@code name-2026-11-22.xlsx} (or .csv), headed in {@code lang}. */
  public static <T> ResponseEntity<byte[]> answer(
      String name, String format, String lang, ZoneId zone, List<Column<T>> columns, List<T> rows) {
    boolean csv = "csv".equalsIgnoreCase(format);
    if (!csv && format != null && !format.isBlank() && !"xlsx".equalsIgnoreCase(format)) {
      throw Refusal.invalid("invalid_format", "format is xlsx or csv.", "format");
    }
    boolean swahili = lang != null && lang.toLowerCase(Locale.ROOT).startsWith("sw");
    List<String> headings =
        columns.stream().map(column -> swahili ? column.sw() : column.en()).toList();
    String file = name + "-" + LocalDate.now(zone) + (csv ? ".csv" : ".xlsx");
    byte[] body =
        csv
            ? csv(headings, columns, rows, zone, swahili)
            : xlsx(name, headings, columns, rows, zone, swahili);
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(file).build().toString())
        .contentType(
            csv
                ? new MediaType("text", "csv", StandardCharsets.UTF_8)
                : MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(body);
  }

  private static <T> byte[] xlsx(
      String name,
      List<String> headings,
      List<Column<T>> columns,
      List<T> rows,
      ZoneId zone,
      boolean swahili) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      Workbook workbook = new Workbook(out, "Pharmacy Core", "1.0");
      Worksheet sheet = workbook.newWorksheet(sheetName(name));
      for (int c = 0; c < headings.size(); c++) {
        sheet.value(0, c, headings.get(c));
        sheet.style(0, c).bold().fillColor("E8EEF9").set();
        sheet.width(c, Math.max(12, Math.min(40, headings.get(c).length() + 4)));
      }
      sheet.freezePane(0, 1);
      for (int r = 0; r < rows.size(); r++) {
        T row = rows.get(r);
        for (int c = 0; c < columns.size(); c++) {
          write(sheet, r + 1, c, columns.get(c).value().apply(row), zone, swahili);
        }
      }
      workbook.finish();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    return out.toByteArray();
  }

  private static void write(
      Worksheet sheet, int r, int c, Object value, ZoneId zone, boolean swahili) {
    switch (value) {
      case null -> {}
      case Number number -> sheet.value(r, c, number);
      case Boolean yes -> sheet.value(r, c, yes(yes, swahili));
      case Instant at -> {
        sheet.value(r, c, LocalDateTime.ofInstant(at, zone));
        sheet.style(r, c).format("yyyy-mm-dd hh:mm").set();
      }
      case LocalDate day -> {
        sheet.value(r, c, day);
        sheet.style(r, c).format("yyyy-mm-dd").set();
      }
      default -> sheet.value(r, c, text(value, zone, swahili));
    }
  }

  private static <T> byte[] csv(
      List<String> headings, List<Column<T>> columns, List<T> rows, ZoneId zone, boolean swahili) {
    StringBuilder text = new StringBuilder("﻿"); // a BOM, so Excel reads it as UTF-8
    text.append(String.join(",", headings.stream().map(Spreadsheet::quoted).toList()));
    for (T row : rows) {
      text.append("\r\n");
      List<String> cells = new ArrayList<>(columns.size());
      for (Column<T> column : columns) {
        Object value = column.value().apply(row);
        cells.add(
            quoted(
                value instanceof Instant at
                    ? at.atZone(zone).toLocalDateTime().toString().replace('T', ' ')
                    : text(value, zone, swahili)));
      }
      text.append(String.join(",", cells));
    }
    return text.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String text(Object value, ZoneId zone, boolean swahili) {
    return switch (value) {
      case null -> "";
      case Boolean yes -> yes(yes, swahili);
      case Enum<?> constant -> constant.name();
      case UUID uid -> uid.toString();
      case TemporalAccessor time -> time.toString();
      default -> String.valueOf(value);
    };
  }

  private static String yes(boolean yes, boolean swahili) {
    return swahili ? (yes ? "Ndiyo" : "Hapana") : (yes ? "Yes" : "No");
  }

  private static String quoted(String value) {
    return "\"" + value.replace("\"", "\"\"") + "\"";
  }

  /** Excel allows 31 characters and none of []:*?/\ in a sheet's name. */
  private static String sheetName(String name) {
    String clean = name.replaceAll("[\\[\\]:*?/\\\\]", " ");
    return clean.length() > 31 ? clean.substring(0, 31) : clean;
  }
}
