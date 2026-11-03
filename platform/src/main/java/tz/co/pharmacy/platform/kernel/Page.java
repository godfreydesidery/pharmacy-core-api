package tz.co.pharmacy.platform.kernel;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** One page of a list and the cursor of the next page, null at the end. */
public record Page<T>(List<T> items, String nextCursor, Long total) {

  public static <T> Page<T> of(List<T> items, String nextCursor) {
    return new Page<>(List.copyOf(items), nextCursor, null);
  }

  /** A page from rows fetched with {@code limit + 1}, newest uid first. */
  public static <T> Page<T> keyset(List<T> rows, PageQuery query, Function<T, UUID> uid) {
    if (rows.size() <= query.limit()) {
      return new Page<>(List.copyOf(rows), null, null);
    }
    List<T> items = List.copyOf(rows.subList(0, query.limit()));
    return new Page<>(items, uid.apply(items.getLast()).toString(), null);
  }

  /** A page from rows fetched with {@code limit + 1} at {@link PageQuery#offset()}. */
  public static <T> Page<T> offset(List<T> rows, PageQuery query) {
    if (rows.size() <= query.limit()) {
      return new Page<>(List.copyOf(rows), null, null);
    }
    return new Page<>(
        List.copyOf(rows.subList(0, query.limit())), "o:" + (query.offset() + query.limit()), null);
  }

  public <R> Page<R> map(Function<T, R> mapping) {
    return new Page<>(items.stream().map(mapping).toList(), nextCursor, total);
  }
}
