package tz.co.pharmacy.platform.composition;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.fx.ports.inbound.ExchangeRates;
import tz.co.pharmacy.modules.fx.ports.inbound.RateView;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
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
 * Refreshes exchange rates from the rate provider every day at 06:00 UTC, for every active tenant
 * that accepts a foreign currency, each as a platform service in that tenant. One tenant's failure
 * does not stop the others (it is recorded with the run). With no provider configured it records
 * nothing. One instance runs it ({@link SingleRunner}).
 */
@Component
class FxRefreshSchedule {

  private static final Logger LOG = LoggerFactory.getLogger(FxRefreshSchedule.class);
  private static final String SERVICE = "fx-refresh";

  private final TenantService tenants;
  private final SettingsService settings;
  private final ExchangeRates rates;
  private final SingleRunner runner;

  FxRefreshSchedule(
      TenantService tenants, SettingsService settings, ExchangeRates rates, SingleRunner runner) {
    this.runner = runner;
    this.tenants = tenants;
    this.settings = settings;
    this.rates = rates;
  }

  @Scheduled(cron = "${pharmacy.integrations.fx.refresh-cron:0 0 6 * * *}", zone = "UTC")
  void refreshAll() {
    runner.runAlone(SERVICE, this::refreshRound);
  }

  private void refreshRound(JobRun run) {
    int pharmacies = 0;
    for (TenantView tenant : activeTenants()) {
      try {
        List<RateView> refreshed = inTenant(tenant.uid(), this::refreshIfForeign);
        if (!refreshed.isEmpty()) {
          pharmacies++;
          LOG.info("Refreshed {} exchange rates for {}", refreshed.size(), tenant.code());
        }
      } catch (RuntimeException failure) {
        LOG.warn("Exchange rate refresh failed for {}: {}", tenant.code(), failure.getMessage());
        run.failed(tenant.uid(), failure);
      }
    }
    run.note("rates refreshed for " + pharmacies + " pharmacies");
  }

  private List<RateView> refreshIfForeign() {
    List<String> accepted = settings.current().acceptedCurrencies();
    return accepted == null || accepted.size() < 2 ? List.of() : rates.refresh();
  }

  private List<TenantView> activeTenants() {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(SERVICE, null, null),
        () -> {
          List<TenantView> all = new ArrayList<>();
          PageQuery query = PageQuery.first(100);
          while (true) {
            Page<TenantView> page = tenants.list(null, query);
            page.items().stream()
                .filter(tenant -> tenant.status() == TenantStatus.ACTIVE)
                .forEach(all::add);
            if (page.nextCursor() == null) {
              return all;
            }
            query = new PageQuery(page.nextCursor(), 100);
          }
        });
  }

  private static <T> T inTenant(UUID tenantUid, java.util.function.Supplier<T> work) {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(SERVICE, null, tenantUid),
        () -> TenantContext.callAs(tenantUid, work));
  }
}
