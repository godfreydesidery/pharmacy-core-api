package tz.co.pharmacy.delivery.api;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * Reads the paging query parameters of a list route: {@code limit}, and either {@code page} (from
 * 1: a numbered page whose answer carries the list's total) or {@code cursor}. Every list route
 * reads {@code page} here, from the request, so each handler names only cursor and limit.
 */
public final class Pages {

  private Pages() {}

  public static PageQuery of(String cursor, Integer limit) {
    return PageQuery.of(cursor, limit, page());
  }

  /**
   * A short list (reference data such as categories or payment methods, which screens also need
   * whole for their pickers): with {@code ?page=} one numbered page of it and its total, without it
   * the whole list as before.
   */
  public static Object slice(java.util.List<?> all) {
    Integer page = page();
    if (page == null) {
      return all;
    }
    PageQuery query = PageQuery.of(null, limit(), page);
    int from = Math.min(query.offset(), all.size());
    int to = Math.min(from + query.limit(), all.size());
    return tz.co.pharmacy.platform.kernel.Page.numbered(all.subList(from, to), all.size());
  }

  private static Integer limit() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servlet)) {
      return null;
    }
    String value = servlet.getRequest().getParameter("limit");
    try {
      return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    } catch (NumberFormatException notANumber) {
      throw Refusal.invalid("invalid_limit", "limit must be between 1 and 100.", "limit");
    }
  }

  /** The request's {@code page} parameter, or null when it pages by cursor. */
  static Integer page() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (!(attributes instanceof ServletRequestAttributes servlet)) {
      return null;
    }
    String value = servlet.getRequest().getParameter("page");
    if (value == null || value.isBlank()) {
      return null;
    }
    try {
      return Integer.valueOf(value.trim());
    } catch (NumberFormatException notANumber) {
      throw Refusal.invalid("invalid_page", "page must be a whole number from 1.", "page");
    }
  }
}
