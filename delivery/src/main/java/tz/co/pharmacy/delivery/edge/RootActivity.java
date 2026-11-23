package tz.co.pharmacy.delivery.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;
import tz.co.pharmacy.platform.persistence.limits.SharedWindows;

/**
 * Records in the tenant's own audit trail that a root administrator acted in it: once an hour per
 * administrator and tenant, so the trail shows the visit without an entry per request (the changes
 * they make are audited one by one anyway). The hour is counted in the database ({@link
 * SharedWindows}), so it holds across every instance of the API.
 */
@Component
public class RootActivity {

  private static final Logger log = LoggerFactory.getLogger(RootActivity.class);
  private static final Duration EVERY = Duration.ofHours(1);
  private static final String KIND = "root-activity";

  private final AuditLog audit;
  private final SharedWindows windows;

  public RootActivity(AuditLog audit, SharedWindows windows) {
    this.audit = audit;
    this.windows = windows;
  }

  /** Call with the request's tenant and actor already in context. */
  void note(UUID rootUid, UUID tenantUid, String method, String path) {
    try {
      if (windows.acquire(KIND, rootUid + "/" + tenantUid, 1, EVERY, Instant.now()).isPresent()) {
        return;
      }
      audit.record(
          "ROOT_ACTED_IN_TENANT", "User", rootUid, null, Map.of("request", method + " " + path));
    } catch (RuntimeException failure) {
      log.warn("Could not audit root administrator activity: {}", failure.getMessage());
    }
  }
}
