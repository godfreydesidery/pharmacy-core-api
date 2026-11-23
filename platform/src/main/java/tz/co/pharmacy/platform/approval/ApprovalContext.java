package tz.co.pharmacy.platform.approval;

import java.util.Optional;

/**
 * The approval a request carries ({@code X-Approval: pap_…}), set by the edge for the request's
 * thread and cleared when it ends; read by {@link Approvals#require}.
 */
public final class ApprovalContext {

  private static final ThreadLocal<String> TOKEN = new ThreadLocal<>();

  private ApprovalContext() {}

  public static void enter(String token) {
    if (token == null || token.isBlank()) {
      TOKEN.remove();
    } else {
      TOKEN.set(token.trim());
    }
  }

  public static void leave() {
    TOKEN.remove();
  }

  public static Optional<String> token() {
    return Optional.ofNullable(TOKEN.get());
  }
}
