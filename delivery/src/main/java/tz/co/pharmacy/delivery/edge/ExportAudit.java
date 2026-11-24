package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Audits every data export (STD-22, FRD-19): any answer of an {@code /api} route that hands over a
 * file ({@code Content-Disposition: attachment}: the list exports, the CSV and FHIR claim batches,
 * the TMDA form, a person's data, a pharmacy's whole export) writes one {@code DATA_EXPORTED} entry
 * with the route, its filters, the file's name and, for the list exports, how many rows it held,
 * once the file is answered. A route that already writes its own export entry (CUSTOMERS_EXPORTED,
 * ADR_EXPORTED) marks the request with {@link #recorded()} and gets no second one.
 */
@Component
public class ExportAudit implements HandlerInterceptor {

  private static final Logger log = LoggerFactory.getLogger(ExportAudit.class);

  /** The request attribute holding the number of rows a list export wrote. */
  public static final String ROWS = ExportAudit.class.getName() + ".rows";

  private static final String RECORDED = ExportAudit.class.getName() + ".recorded";

  private final AuditLog audit;

  public ExportAudit(AuditLog audit) {
    this.audit = audit;
  }

  /** Notes how many rows the current request's export holds. */
  public static void rows(int count) {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null) {
      attributes.setAttribute(ROWS, count, RequestAttributes.SCOPE_REQUEST);
    }
  }

  /** The current request's export is audited by its route already. */
  public static void recorded() {
    RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
    if (attributes != null) {
      attributes.setAttribute(RECORDED, Boolean.TRUE, RequestAttributes.SCOPE_REQUEST);
    }
  }

  @Override
  public void afterCompletion(
      HttpServletRequest request, HttpServletResponse response, Object handler, Exception failure) {
    String disposition = response.getHeader(HttpHeaders.CONTENT_DISPOSITION);
    if (disposition == null
        || !disposition.toLowerCase(java.util.Locale.ROOT).startsWith("attachment")
        || response.getStatus() >= 400
        || failure != null
        || Boolean.TRUE.equals(request.getAttribute(RECORDED))) {
      return;
    }
    try {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("request", request.getMethod() + " " + EdgeFilter.path(request));
      String query = request.getQueryString();
      if (query != null && !query.isBlank()) {
        details.put("query", query.length() > 500 ? query.substring(0, 500) : query);
      }
      details.put("file", fileName(disposition));
      if (request.getAttribute(ROWS) instanceof Integer rows) {
        details.put("rows", rows);
      }
      audit.record("DATA_EXPORTED", "Export", null, null, details);
    } catch (RuntimeException notAudited) {
      log.warn(
          "Could not audit the export of {}: {}", request.getRequestURI(), notAudited.getMessage());
    }
  }

  private static String fileName(String disposition) {
    int at = disposition.indexOf("filename=");
    if (at < 0) {
      return null;
    }
    String name = disposition.substring(at + "filename=".length()).trim();
    int end = name.indexOf(';');
    name = end < 0 ? name : name.substring(0, end);
    return name.replace("\"", "");
  }
}
