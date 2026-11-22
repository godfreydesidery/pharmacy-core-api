package tz.co.pharmacy.platform.kernel;

import java.util.UUID;

/**
 * One page request, of {@code limit} rows from 1 to 100 (25 by default), either:
 *
 * <ul>
 *   <li>numbered: {@code page} 1, 2, 3… The answer carries the total, so a table can show its page
 *       numbers and "26–50 of 312"; or
 *   <li>by cursor: the previous page's {@code nextCursor} (null for the first). A keyset list's
 *       cursor is the last uid it returned; a list in another order pages by offset, {@code o:<n>}.
 * </ul>
 */
public record PageQuery(String cursor, int limit, Integer page) {

  public PageQuery(String cursor, int limit) {
    this(cursor, limit, null);
  }

  public static final int DEFAULT_LIMIT = 25;
  public static final int MAX_LIMIT = 100;

  public static PageQuery of(String cursor, Integer limit) {
    return of(cursor, limit, null);
  }

  /** With {@code page} (from 1) the list is numbered and counted; otherwise it pages by cursor. */
  public static PageQuery of(String cursor, Integer limit, Integer page) {
    int size = limit == null ? DEFAULT_LIMIT : limit;
    if (size < 1 || size > MAX_LIMIT) {
      throw Refusal.invalid("invalid_limit", "limit must be between 1 and 100.", "limit");
    }
    if (page != null && page < 1) {
      throw Refusal.invalid("invalid_page", "page starts at 1.", "page");
    }
    return new PageQuery(cursor == null || cursor.isBlank() ? null : cursor.trim(), size, page);
  }

  /** Whether the list is asked for by page number, and so with its total. */
  public boolean numbered() {
    return page != null;
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
    if (page != null) {
      return (page - 1) * limit;
    }
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
