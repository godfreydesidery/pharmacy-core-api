package tz.co.pharmacy.delivery.api.v1.imports;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.List;
import org.dhatim.fastexcel.Workbook;
import org.dhatim.fastexcel.Worksheet;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * An import's template: a sheet with the headings (required ones bold, marked *) and one example
 * row to overwrite, and an Instructions sheet saying what goes in each column.
 */
final class ImportTemplate {

  private ImportTemplate() {}

  static ResponseEntity<byte[]> answer(Importer<?> importer, boolean swahili) {
    String file = importer.kind() + "-template.xlsx";
    return ResponseEntity.ok()
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment().filename(file).build().toString())
        .contentType(
            MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
        .body(workbook(importer, swahili));
  }

  static byte[] workbook(Importer<?> importer, boolean swahili) {
    List<ImportColumn> columns = importer.columns();
    List<List<Object>> rows = importer.templateRows();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      Workbook workbook = new Workbook(out, "Pharmacy Core", "1.0");
      Worksheet sheet = workbook.newWorksheet(importer.kind());
      for (int c = 0; c < columns.size(); c++) {
        String heading = columns.get(c).heading(swahili);
        sheet.value(0, c, heading);
        var style = sheet.style(0, c).fillColor(columns.get(c).required() ? "FDE7C8" : "E8EEF9");
        if (columns.get(c).required()) {
          style.bold();
        }
        style.set();
        sheet.width(c, Math.max(14, Math.min(40, heading.length() + 4)));
      }
      for (int r = 0; r < rows.size(); r++) {
        List<Object> row = rows.get(r);
        for (int c = 0; c < columns.size(); c++) {
          Object value = c < row.size() ? row.get(c) : null;
          switch (value) {
            case null -> {}
            case Number number -> sheet.value(r + 1, c, number);
            case LocalDate day -> {
              sheet.value(r + 1, c, day);
              sheet.style(r + 1, c).format("yyyy-mm-dd").set();
            }
            default -> sheet.value(r + 1, c, String.valueOf(value));
          }
        }
      }
      sheet.freezePane(0, 1);

      Worksheet help = workbook.newWorksheet(swahili ? "Maelekezo" : "Instructions");
      help.value(0, 0, swahili ? "Safu" : "Column");
      help.value(0, 1, swahili ? "Lazima" : "Required");
      help.value(0, 2, swahili ? "Nini cha kuandika" : "What to enter");
      for (int c = 0; c < 3; c++) {
        help.style(0, c).bold().fillColor("E8EEF9").set();
      }
      help.width(0, 26);
      help.width(1, 10);
      help.width(2, 100);
      for (int i = 0; i < columns.size(); i++) {
        ImportColumn column = columns.get(i);
        help.value(i + 1, 0, column.heading(swahili));
        help.value(i + 1, 1, column.required() ? (swahili ? "Ndiyo" : "Yes") : "");
        help.value(i + 1, 2, column.help(swahili));
        help.style(i + 1, 2).wrapText(true).set();
      }
      int notes = columns.size() + 2;
      for (String note : notes(swahili)) {
        help.value(notes++, 0, note);
      }
      workbook.finish();
    } catch (IOException failure) {
      throw new UncheckedIOException(failure);
    }
    return out.toByteArray();
  }

  private static List<String> notes(boolean swahili) {
    return swahili
        ? List.of(
            "Andika kuanzia safu ya 2; futa mfano. Vichwa vinaweza kuwa kwa Kiingereza au"
                + " Kiswahili.",
            "Safu ikikosekana kabisa, thamani iliyopo haibadilishwi. Kisanduku kitupu kinafuta"
                + " thamani.",
            "Kagua faili kwanza: hakuna kinachohifadhiwa mpaka faili lote liwe sahihi.",
            "Tarehe: 2026-12-31 au 31/12/2026. Ndiyo/Hapana: Ndiyo au Hapana (Yes au No).")
        : List.of(
            "Start on row 2 and replace the example. Headings may be in English or Swahili.",
            "A column left out keeps what is there. An empty cell clears the value.",
            "Check the file first: nothing is saved until the whole file is right.",
            "Dates: 2026-12-31 or 31/12/2026. Yes/No: Yes or No (Ndiyo or Hapana).");
  }
}
