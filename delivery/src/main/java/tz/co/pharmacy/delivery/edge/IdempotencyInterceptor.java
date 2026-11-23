package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.RefusalKind;
import tz.co.pharmacy.platform.persistence.IdempotencyKeys;
import tz.co.pharmacy.platform.persistence.IdempotencyKeys.InProgress;
import tz.co.pharmacy.platform.persistence.IdempotencyKeys.Mismatch;
import tz.co.pharmacy.platform.persistence.IdempotencyKeys.Replay;
import tz.co.pharmacy.platform.persistence.IdempotencyKeys.Reserved;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The handler half of {@link Idempotent}, registered after {@link AccessInterceptor}: only a caller
 * allowed to use the route reaches it. For an annotated handler with an {@code Idempotency-Key}, it
 * reserves the key (fingerprint: SHA-256 of the body), replays a completed response, or refuses a
 * reuse (422 {@code idempotency_key_reused}) or a retry of a request still running (409 {@code
 * idempotency_in_progress}). {@link IdempotencyFilter} keeps or frees the key once the handler has
 * answered.
 */
@Component
public class IdempotencyInterceptor implements HandlerInterceptor {

  static final int MAX_KEY_LENGTH = 255;

  private final IdempotencyKeys keys;

  public IdempotencyInterceptor(IdempotencyKeys keys) {
    this.keys = keys;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws IOException {
    if (!(handler instanceof HandlerMethod method)
        || request.getDispatcherType() != DispatcherType.REQUEST
        || !method.hasMethodAnnotation(Idempotent.class)
        || !(request.getAttribute(IdempotencyFilter.BODY) instanceof byte[] body)
        || TenantContext.tenantUid().isEmpty()) {
      return true;
    }
    String key = key(request.getHeader(IdempotencyFilter.HEADER));
    IdempotencyKeys.Outcome outcome =
        keys.reserve(
            key,
            request.getMethod(),
            EdgeFilter.path(request),
            sha256(body),
            ExecutionContext.current()
                .map(frame -> frame.actor() == null ? null : frame.actor().uid())
                .orElse(null));
    return switch (outcome) {
      case Reserved reserved -> {
        request.setAttribute(IdempotencyFilter.RESERVED, key);
        yield true;
      }
      case Replay replay -> {
        response.setStatus(replay.status());
        response.setHeader(IdempotencyFilter.REPLAYED_HEADER, "true");
        if (replay.contentType() != null) {
          response.setContentType(replay.contentType());
        }
        if (replay.body() != null) {
          response.getOutputStream().write(replay.body().getBytes(StandardCharsets.UTF_8));
        }
        yield false;
      }
      case InProgress inProgress ->
          throw Refusal.conflict(
              "idempotency_in_progress",
              "A request with this Idempotency-Key is still being processed; retry shortly.");
      case Mismatch mismatch ->
          throw Refusal.invalid(
              "idempotency_key_reused",
              "This Idempotency-Key was already used for a different request; use a new key.");
    };
  }

  /**
   * The key: 1 to 255 visible ASCII characters. The draft sends it as a structured-field string
   * ({@code "abc"}); the quotes are optional here.
   */
  static String key(String header) {
    String key = header == null ? "" : header.trim();
    if (key.length() >= 2 && key.startsWith("\"") && key.endsWith("\"")) {
      key = key.substring(1, key.length() - 1);
    }
    if (key.isEmpty()
        || key.length() > MAX_KEY_LENGTH
        || key.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
      throw new Refusal(
          RefusalKind.MALFORMED,
          "invalid_idempotency_key",
          "Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " visible ASCII characters.",
          null);
    }
    return key;
  }

  static String sha256(byte[] body) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
