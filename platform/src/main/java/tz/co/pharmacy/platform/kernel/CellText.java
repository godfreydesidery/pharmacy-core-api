package tz.co.pharmacy.platform.kernel;

/**
 * Text cells of an exported CSV or spreadsheet, made safe to open in Excel, LibreOffice or Google
 * Sheets: a value beginning with {@code =}, {@code +}, {@code -}, {@code @}, a tab or a carriage
 * return could be taken for a formula (and run one), so it gets a leading {@code '} (OWASP, "CSV
 * Injection"). Only text: a number is written as a number and never passes through here, so a
 * negative amount stays a number.
 */
public final class CellText {

  private CellText() {}

  /** The text as it may be written in a cell. */
  public static String neutralised(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    return switch (text.charAt(0)) {
      case '=', '+', '-', '@', '\t', '\r' -> "'" + text;
      default -> text;
    };
  }
}
