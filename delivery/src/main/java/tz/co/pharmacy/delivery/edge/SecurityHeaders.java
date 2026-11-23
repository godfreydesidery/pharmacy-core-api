package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Response headers that keep an API response from being sniffed, framed or cached: the API serves
 * JSON only, so its Content-Security-Policy allows nothing. Account routes are never cached.
 */
final class SecurityHeaders {

  private SecurityHeaders() {}

  static void apply(HttpServletRequest request, HttpServletResponse response) {
    response.setHeader("X-Content-Type-Options", "nosniff");
    response.setHeader("X-Frame-Options", "DENY");
    response.setHeader("Referrer-Policy", "no-referrer");
    response.setHeader(
        "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'; base-uri 'none'");
    response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
    response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
    if (EdgeFilter.path(request).startsWith(EdgeFilter.AUTH_PREFIX)) {
      response.setHeader("Cache-Control", "no-store");
    }
  }
}
