package tz.co.pharmacy.delivery.edge;

import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Records every request of a root administrator (adversarial review FRD-19, STD-22): in the
 * pharmacy's own audit trail when it acts in one ({@code ROOT_ACTED_IN_TENANT}), in the platform's
 * otherwise ({@code ROOT_REQUEST}), with the route, the answer's status and its outcome, and (as
 * every entry) the client address, user agent and session. Before, a pharmacy's trail showed a
 * visit once an hour, so what root read in between was not on record. The changes root makes are
 * audited one by one as well. Not recorded: root's own account routes outside any pharmacy (sign
 * in, profile) and the live notification stream, which stays open.
 */
@Component
public class RootActivity {

  private static final Logger log = LoggerFactory.getLogger(RootActivity.class);

  private final AuditLog audit;

  public RootActivity(AuditLog audit) {
    this.audit = audit;
  }

  /** Whether a root administrator's request is recorded. */
  static boolean noted(HttpServletRequest request) {
    String path = EdgeFilter.path(request);
    return !EdgeFilter.STREAM_PATH.equals(path) && !EdgeFilter.accountRoute(request);
  }

  /**
   * Call once the request is answered, with its tenant (null for the platform) and actor still in
   * context.
   */
  void note(UUID rootUid, UUID tenantUid, String method, String path, int status) {
    try {
      Map<String, Object> details = new LinkedHashMap<>();
      details.put("request", method + " " + path);
      details.put("status", status);
      audit.record(
          tenantUid == null ? "ROOT_REQUEST" : "ROOT_ACTED_IN_TENANT",
          "User",
          rootUid,
          null,
          details,
          status >= 400 ? "FAILURE" : "SUCCESS");
    } catch (RuntimeException failure) {
      log.warn("Could not audit root administrator activity: {}", failure.getMessage());
    }
  }
}
