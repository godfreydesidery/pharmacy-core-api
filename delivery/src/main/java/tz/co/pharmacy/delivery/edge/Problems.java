package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Writes an RFC 9457 problem from the edge, before Spring MVC handles the request. */
final class Problems {

  static final String TYPE_PREFIX = "https://pharmacy-core/problems/";

  private Problems() {}

  static void write(HttpServletResponse response, Refusal refusal, String correlationId)
      throws IOException {
    int status = refusal.kind().status();
    response.setStatus(status);
    if (refusal.retryAfterSeconds() != null) {
      response.setHeader("Retry-After", String.valueOf(refusal.retryAfterSeconds()));
    }
    response.setContentType("application/problem+json");
    response.setCharacterEncoding("UTF-8");
    String body =
        "{"
            + field("type", TYPE_PREFIX + refusal.reason())
            + ","
            + field("title", title(status))
            + ",\"status\":"
            + status
            + ","
            + field("detail", refusal.getMessage())
            + ","
            + field("reason", refusal.reason())
            + (refusal.field() == null ? "" : "," + field("field", refusal.field()))
            + (refusal.retryAfterSeconds() == null
                ? ""
                : ",\"retryAfterSeconds\":" + refusal.retryAfterSeconds())
            + ","
            + field("correlationId", correlationId)
            + "}";
    response.getWriter().write(body);
  }

  static String title(int status) {
    return switch (status) {
      case 400 -> "Malformed request";
      case 401 -> "Unauthenticated";
      case 403 -> "Forbidden";
      case 404 -> "Not found";
      case 409 -> "Conflict";
      case 413 -> "Payload too large";
      case 415 -> "Unsupported media type";
      case 422 -> "Unprocessable request";
      case 423 -> "Locked";
      case 429 -> "Too many requests";
      case 503 -> "Service unavailable";
      default -> "Error";
    };
  }

  private static String field(String name, String value) {
    return "\"" + name + "\":" + (value == null ? "null" : "\"" + escape(value) + "\"");
  }

  private static String escape(String value) {
    StringBuilder out = new StringBuilder();
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.toString();
  }
}
