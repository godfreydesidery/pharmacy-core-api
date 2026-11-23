package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tz.co.pharmacy.platform.persistence.IdempotencyKeys;

/**
 * The servlet half of {@link tz.co.pharmacy.delivery.api.Idempotent}. It runs inside {@link
 * EdgeFilter} (so the tenant is known) for a POST that carries {@code Idempotency-Key} with a JSON
 * (or empty) body: it reads the body once, so the interceptor can fingerprint it and the handler
 * still reads it, and buffers the response. When {@link IdempotencyInterceptor} reserved the key
 * for this request, a 2xx outcome is kept for replay and any other outcome frees the key. Without
 * the header nothing changes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class IdempotencyFilter extends OncePerRequestFilter {

  public static final String HEADER = "Idempotency-Key";
  public static final String REPLAYED_HEADER = "Idempotent-Replayed";

  /** The request attribute holding the key this request reserved. */
  static final String RESERVED = IdempotencyFilter.class.getName() + ".reserved";

  /** The request attribute holding the buffered body. */
  static final String BODY = IdempotencyFilter.class.getName() + ".body";

  private static final Logger LOG = LoggerFactory.getLogger(IdempotencyFilter.class);

  private final IdempotencyKeys keys;

  public IdempotencyFilter(IdempotencyKeys keys) {
    this.keys = keys;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String contentType = request.getContentType();
    return !"POST".equals(request.getMethod())
        || request.getHeader(HEADER) == null
        || !EdgeFilter.path(request).startsWith("/api/")
        // A multipart upload is parsed by the container from the raw stream: never buffered here.
        || (contentType != null && !contentType.toLowerCase().contains("json"));
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    byte[] body = request.getInputStream().readAllBytes();
    request.setAttribute(BODY, body);
    ContentCachingResponseWrapper buffered = new ContentCachingResponseWrapper(response);
    boolean finished = false;
    try {
      chain.doFilter(new BufferedRequest(request, body), buffered);
      finished = true;
    } finally {
      Object reserved = request.getAttribute(RESERVED);
      if (reserved instanceof String key) {
        settle(key, buffered, finished);
      }
      buffered.copyBodyToResponse();
    }
  }

  /** Keeps a 2xx response for replay; frees the key after any other outcome. */
  private void settle(String key, ContentCachingResponseWrapper response, boolean finished) {
    int status = response.getStatus();
    try {
      if (finished && status >= 200 && status < 300) {
        keys.complete(
            key,
            status,
            response.getContentType(),
            new String(response.getContentAsByteArray(), StandardCharsets.UTF_8));
      } else {
        keys.release(key);
      }
    } catch (RuntimeException failure) {
      // The key stays in progress until it expires: safer than letting a retry move money twice.
      LOG.warn("Could not settle idempotency key: {}", failure.getMessage());
    }
  }

  /** The request with its body read once and served again to the handler. */
  private static final class BufferedRequest extends HttpServletRequestWrapper {

    private final byte[] body;

    BufferedRequest(HttpServletRequest request, byte[] body) {
      super(request);
      this.body = body;
    }

    @Override
    public ServletInputStream getInputStream() {
      ByteArrayInputStream in = new ByteArrayInputStream(body);
      return new ServletInputStream() {
        @Override
        public int read() {
          return in.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
          return in.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
          return in.available() == 0;
        }

        @Override
        public boolean isReady() {
          return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
          throw new UnsupportedOperationException("Synchronous reads only");
        }
      };
    }

    @Override
    public BufferedReader getReader() {
      String encoding = getCharacterEncoding();
      return new BufferedReader(
          new InputStreamReader(
              new ByteArrayInputStream(body),
              encoding == null
                  ? StandardCharsets.UTF_8
                  : java.nio.charset.Charset.forName(encoding)));
    }

    @Override
    public int getContentLength() {
      return body.length;
    }

    @Override
    public long getContentLengthLong() {
      return body.length;
    }
  }
}
