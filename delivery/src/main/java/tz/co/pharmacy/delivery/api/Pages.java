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
