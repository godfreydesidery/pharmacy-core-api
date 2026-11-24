package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import tools.jackson.databind.json.JsonMapper;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * RFC 9457 problems. {@link #write} answers from the edge, before Spring MVC handles the request;
 * {@link ProblemAdvice} answers for a handler. Both take a refusal's members from {@link #members},
 * so a refusal carries the same members (its {@code reason}, {@code field}, {@code
 * retryAfterSeconds} and every {@link Refusal#with} value) whichever path answers it.
 */
final class Problems {

  static final String TYPE_PREFIX = "https://pharmacy-core/problems/";

  private static final JsonMapper JSON = JsonMapper.builder().build();

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
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", TYPE_PREFIX + refusal.reason());
    body.put("title", title(status));
    body.put("status", status);
    body.put("detail", refusal.getMessage());
    members(refusal, correlationId, body::put);
    response.getWriter().write(JSON.writeValueAsString(body));
  }

  /**
   * The members a refusal's problem carries besides type, title, status and detail: {@code reason},
   * {@code field} (when it names one), {@code correlationId}, its extras, and {@code
   * retryAfterSeconds} (when it has one). An extra never replaces one of the standard members.
   */
  static void members(Refusal refusal, String correlationId, BiConsumer<String, Object> member) {
    member.accept("reason", refusal.reason());
    if (refusal.field() != null) {
      member.accept("field", refusal.field());
    }
    member.accept("correlationId", correlationId);
    refusal
        .extras()
        .forEach(
            (name, value) -> {
              if (!STANDARD.contains(name)) {
                member.accept(name, value);
              }
            });
    if (refusal.retryAfterSeconds() != null) {
      member.accept("retryAfterSeconds", refusal.retryAfterSeconds());
    }
  }

  private static final java.util.Set<String> STANDARD =
      java.util.Set.of(
          "type",
          "title",
          "status",
          "detail",
          "instance",
          "reason",
          "field",
          "correlationId",
          "retryAfterSeconds");

  static String title(int status) {
    return switch (status) {
      case 400 -> "Malformed request";
      case 401 -> "Unauthenticated";
      case 403 -> "Forbidden";
      case 404 -> "Not found";
      case 409 -> "Conflict";
      case 412 -> "Precondition failed";
      case 413 -> "Payload too large";
      case 415 -> "Unsupported media type";
      case 422 -> "Unprocessable request";
      case 423 -> "Locked";
      case 428 -> "Precondition required";
      case 429 -> "Too many requests";
      case 503 -> "Service unavailable";
      default -> "Error";
    };
  }
}
