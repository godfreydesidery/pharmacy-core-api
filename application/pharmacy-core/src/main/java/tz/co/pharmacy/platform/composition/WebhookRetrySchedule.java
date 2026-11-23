package tz.co.pharmacy.platform.composition;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.integrations.ports.inbound.Webhooks;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.jobs.JobRun;

/**
 * Every minute, each active pharmacy's failed webhook deliveries that are due are sent again, so a
 * pharmacy's system that was down gets the events it missed once it is back (the chaos run found
 * they were lost for good before). One instance runs a round at a time ({@link SingleRunner}, a
 * PostgreSQL advisory lock), and each delivery is claimed with {@code FOR UPDATE SKIP LOCKED} and a
 * lease, so a delivery is never sent twice by two instances.
 */
@Component
class WebhookRetrySchedule {

  private static final Logger log = LoggerFactory.getLogger(WebhookRetrySchedule.class);
  private static final String SERVICE = "webhook-retries";

  private final TenantService tenants;
  private final Webhooks webhooks;
  private final SingleRunner runner;

  WebhookRetrySchedule(TenantService tenants, Webhooks webhooks, SingleRunner runner) {
    this.tenants = tenants;
    this.webhooks = webhooks;
    this.runner = runner;
  }

  @Scheduled(fixedDelayString = "${pharmacy.integrations.webhooks.retry-every-ms:60000}")
  void retry() {
    runner.runAlone(SERVICE, this::retryAll);
  }

  private void retryAll(JobRun run) {
    int tried = 0;
    for (UUID tenant : activeTenants()) {
      try {
        tried +=
            ExecutionContext.callAs(
                ExecutionContext.Frame.service(SERVICE, null, tenant),
                () -> TenantContext.callAs(tenant, webhooks::retryDue));
      } catch (RuntimeException failure) {
        log.warn("Webhook retries for tenant {} failed: {}", tenant, failure.getMessage());
        run.failed(tenant, failure);
      }
    }
    if (tried > 0) {
      log.info("Webhook retries: {} deliveries tried again", tried);
      run.note(tried + " deliveries tried again");
    }
  }

  private List<UUID> activeTenants() {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(SERVICE, null, null),
        () -> {
          List<UUID> all = new ArrayList<>();
          PageQuery query = PageQuery.first(100);
          while (true) {
            Page<TenantView> page = tenants.list(null, query);
            page.items().stream()
                .filter(t -> t.status() == TenantStatus.ACTIVE)
                .forEach(t -> all.add(t.uid()));
            if (page.nextCursor() == null) {
              return all;
            }
            query = new PageQuery(page.nextCursor(), 100);
          }
        });
  }
}
