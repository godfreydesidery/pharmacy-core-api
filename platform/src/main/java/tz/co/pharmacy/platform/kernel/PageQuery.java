package tz.co.pharmacy.platform.kernel;

import java.util.UUID;

/**
 * One page request: an opaque {@code cursor} (the previous page's {@code nextCursor}, or null for
 * the first page) and a {@code limit} from 1 to 100, 25 by default. A keyset list's cursor is the
 * last uid it returned; a list in another order pages by offset, {@code o:<n>}.
 */
public record PageQuery(String cursor, int limit) {

  public static final int DEFAULT_LIMIT = 25;
  public static final int MAX_LIMIT = 100;

  public static PageQuery of(String cursor, Integer limit) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw Refusal.invalid("invalid_limit", "limit must be between 1 and 100.", "limit");
    }
    return new PageQuery(cursor == null || cursor.isBlank() ? null : cursor.trim(), size);
  }

  public static PageQuery first(int limit) {
    return new PageQuery(null, limit);
  }

  /** The uid after which a keyset page starts, or null on the first page. */
  public UUID afterUid() {
    if (cursor == null) {
      return null;
    }
    try {
      return UUID.fromString(cursor);
    } catch (IllegalArgumentException notAUid) {
      throw Refusal.invalid("invalid_cursor", "cursor is not one this list returned.", "cursor");
    }
  }

  /** The row an offset page starts at. */
  public int offset() {
    if (cursor == null) {
      return 0;
    }
    if (!cursor.startsWith("o:")) {
      throw Refusal.invalid("invalid_cursor", "cursor is not one this list returned.", "cursor");
    }
    try {
      return Math.max(0, Integer.parseInt(cursor.substring(2)));
    } catch (NumberFormatException notANumber) {
      throw Refusal.invalid("invalid_cursor", "cursor is not one this list returned.", "cursor");
    }
  }
}
