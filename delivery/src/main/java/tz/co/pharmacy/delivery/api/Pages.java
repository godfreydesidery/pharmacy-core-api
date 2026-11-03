package tz.co.pharmacy.delivery.api;

import tz.co.pharmacy.platform.kernel.PageQuery;

/** Reads the cursor and limit query parameters of a list route. */
public final class Pages {

  private Pages() {}

  public static PageQuery of(String cursor, Integer limit) {
    return PageQuery.of(cursor, limit);
  }
}
