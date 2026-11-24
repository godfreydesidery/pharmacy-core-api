package tz.co.pharmacy.platform.kernel;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Where a line sits in an account statement, the order its lines are read in: by day, then by kind
 * ({@code order}: opening 0, receipt 1, payment 2, return 3), then by document number and uid. A
 * statement read page by page continues after the last line's key (keyset paging, PERF-5).
 */
public record StatementKey(LocalDate date, int order, String number, UUID uid) {

  /** As the cursor of the next page: opaque to the client. */
  public String cursor() {
    String text = date + "|" + order + "|" + uid + "|" + (number == null ? "" : number);
    return java.util.Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  /** 400 invalid_cursor for anything {@link #cursor()} did not make; null for none. */
  public static StatementKey ofCursor(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String text =
          new String(
              java.util.Base64.getUrlDecoder().decode(cursor.trim()),
              java.nio.charset.StandardCharsets.UTF_8);
      String[] parts = text.split("[|]", 4);
      return new StatementKey(
          LocalDate.parse(parts[0]),
          Integer.parseInt(parts[1]),
          parts[3].isEmpty() ? null : parts[3],
          UUID.fromString(parts[2]));
    } catch (RuntimeException malformed) {
      throw Refusal.invalid("invalid_cursor", "The cursor is not valid.", "cursor");
    }
  }
}
