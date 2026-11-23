package tz.co.pharmacy.platform.approval;

import java.util.Arrays;
import java.util.List;

/**
 * The approvals a request carries, set by the edge for the request's thread and cleared when it
 * ends; read by {@link Approvals#require}. {@code X-Approval} is a list header (RFC 9110 §5.6.1):
 * one request may need two approvals, such as a late return of a damaged controlled medicine
 * (LATE_RETURN and CONTROLLED_WRITE_OFF), so it carries {@code X-Approval: pap_a, pap_b}; each
 * {@code require} uses the one that matches its action.
 */
public final class ApprovalContext {

  /** More than this many approvals on one request is not a real request. */
  static final int MAX_TOKENS = 4;

  private static final ThreadLocal<List<String>> TOKENS = new ThreadLocal<>();

  private ApprovalContext() {}

  public static void enter(String header) {
    if (header == null || header.isBlank()) {
      TOKENS.remove();
      return;
    }
    List<String> tokens =
        Arrays.stream(header.split(","))
            .map(String::trim)
            .filter(token -> !token.isEmpty())
            .distinct()
            .limit(MAX_TOKENS)
            .toList();
    if (tokens.isEmpty()) {
      TOKENS.remove();
    } else {
      TOKENS.set(tokens);
    }
  }

  public static void leave() {
    TOKENS.remove();
  }

  /** The approvals the request carries, in the order sent; empty when none. */
  public static List<String> tokens() {
    List<String> tokens = TOKENS.get();
    return tokens == null ? List.of() : tokens;
  }
}
