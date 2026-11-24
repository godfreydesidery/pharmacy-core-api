package tz.co.pharmacy.platform.composition;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.finance.ports.inbound.ExpiryProvisions;
import tz.co.pharmacy.modules.finance.ports.inbound.MonthEndRuns;
import tz.co.pharmacy.modules.finance.ports.inbound.VatApportionments;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantStatus;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.TenantCalendar;
import tz.co.pharmacy.platform.persistence.SingleRunner;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.jobs.JobRun;

/**
 * The month-end jobs of the owner's decisions of 24 Nov 2026, each on one instance ({@link
 * SingleRunner}, recorded in {@code platform.job_runs}), every hour:
 *
 * <ul>
 *   <li>{@code expiry-provision} (STD-3): the expiry write-down of the month that just ended;
 *   <li>{@code vat-apportionment} (STD-2): the month's VAT apportionment, and in the financial
 *       year's last month the true-up.
 * </ul>
 *
 * A pharmacy is measured on the first days of its own month (days 1 to {@value #CATCH_UP_DAYS} in
 * its time zone, so an instance that was down on the 1st still runs it), for the month before; a
 * month is recorded once, so the later wakes find it done. Released after a month's seventh day,
 * the job's first run is for that month (none is made for months long past); an admin may run the
 * last complete month by hand ({@code POST /finance/expiry-provision/runs}, {@code POST
 * /finance/vat-apportionment/runs}).
 */
@Component
class MonthEndSchedule {

  private static final Logger LOG = LoggerFactory.getLogger(MonthEndSchedule.class);
  static final String PROVISION = "expiry-provision";
  static final String APPORTIONMENT = "vat-apportionment";

  /** The month's first days on which the last month is measured. */
  static final int CATCH_UP_DAYS = 7;

  private final TenantService tenants;
  private final MonthEndRuns months;
  private final TenantCalendar calendar;
  private final SingleRunner runner;

  MonthEndSchedule(
      TenantService tenants, MonthEndRuns months, TenantCalendar calendar, SingleRunner runner) {
    this.tenants = tenants;
    this.months = months;
    this.calendar = calendar;
    this.runner = runner;
  }

  @Scheduled(cron = "${pharmacy.month-end.provision-cron:0 10 * * * *}")
  void provisions() {
    runner.runAlone(PROVISION, this::provisionRound);
  }

  @Scheduled(cron = "${pharmacy.month-end.apportionment-cron:0 20 * * * *}")
  void apportionments() {
    runner.runAlone(APPORTIONMENT, this::apportionmentRound);
  }

  void provisionRound(JobRun run) {
    int done = 0;
    for (TenantView tenant : activeTenants(PROVISION)) {
      try {
        ExpiryProvisions.ExpiryProvisionRun measured =
            inTenant(
                PROVISION,
                tenant.uid(),
                () -> due() ? months.expiryProvision(months.lastCompleteMonth()) : null);
        if (measured != null) {
          done++;
        }
      } catch (RuntimeException failure) {
        LOG.warn("Expiry provision of {} failed: {}", tenant.code(), failure.getMessage());
        run.failed(tenant.uid(), failure);
      }
    }
    run.note(done + " pharmacies measured");
  }

  void apportionmentRound(JobRun run) {
    int done = 0;
    for (TenantView tenant : activeTenants(APPORTIONMENT)) {
      try {
        List<VatApportionments.VatApportionmentRun> runs =
            inTenant(
                APPORTIONMENT,
                tenant.uid(),
                () -> due() ? months.vatApportionment(months.lastCompleteMonth()) : List.of());
        if (!runs.isEmpty()) {
          done++;
        }
      } catch (RuntimeException failure) {
        LOG.warn("VAT apportionment of {} failed: {}", tenant.code(), failure.getMessage());
        run.failed(tenant.uid(), failure);
      }
    }
    run.note(done + " pharmacies apportioned");
  }

  /** On the first days of the pharmacy's own month. */
  private boolean due() {
    return due(calendar.today());
  }

  /** Whether {@code today} (the pharmacy's) is one of its month's first days. */
  static boolean due(LocalDate today) {
    return today.getDayOfMonth() <= CATCH_UP_DAYS;
  }

  private List<TenantView> activeTenants(String service) {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(service, null, null),
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

  private static <T> T inTenant(String service, UUID tenantUid, Supplier<T> work) {
    return ExecutionContext.callAs(
        ExecutionContext.Frame.service(service, null, tenantUid),
        () -> TenantContext.callAs(tenantUid, work));
  }
}
