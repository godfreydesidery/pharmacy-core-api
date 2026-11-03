package tz.co.pharmacy.delivery.edge;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Records in the tenant's own audit trail that a root administrator acted in it: once an hour per
 * administrator and tenant, so the trail shows the visit without an entry per request (the changes
 * they make are audited one by one anyway).
 */
@Component
public class RootActivity {

  private static final Logger log = LoggerFactory.getLogger(RootActivity.class);
  private static final Duration EVERY = Duration.ofHours(1);

  private final AuditLog audit;
  private final Map<String, Instant> lastNoted = new ConcurrentHashMap<>();

  public RootActivity(AuditLog audit) {
    this.audit = audit;
  }

  /** Call with the request's tenant and actor already in context. */
  void note(UUID rootUid, UUID tenantUid, String method, String path) {
    String key = rootUid + "/" + tenantUid;
    Instant now = Instant.now();
    Instant last = lastNoted.get(key);
    if (last != null && last.isAfter(now.minus(EVERY))) {
      return;
    }
    lastNoted.put(key, now);
    try {
      audit.record(
          "ROOT_ACTED_IN_TENANT", "User", rootUid, null, Map.of("request", method + " " + path));
    } catch (RuntimeException failure) {
      log.warn("Could not audit root administrator activity: {}", failure.getMessage());
    }
  }

  @Scheduled(fixedDelay = 3_600_000)
  void prune() {
    Instant oldest = Instant.now().minus(EVERY);
    lastNoted.values().removeIf(at -> at.isBefore(oldest));
  }
}
