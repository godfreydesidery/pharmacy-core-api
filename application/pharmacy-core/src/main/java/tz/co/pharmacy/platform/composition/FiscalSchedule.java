package tz.co.pharmacy.platform.composition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentView;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocuments;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalStatus;
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
 * The fiscal job (STD-1), every 15 minutes on one instance ({@link SingleRunner}, recorded in
 * {@code platform.job_runs} as {@code fiscal-documents}), for every active pharmacy:
 *
 * <ol>
 *   <li>sends again the receipts, credit notes and Z reports still PENDING after 10 minutes (the
 *       outbox resends a sale's event too; the document's lease keeps the two from both sending);
 *   <li>closes each branch's past days: a daily Z report per branch and local day that has issued
 *       documents no Z report closed yet (a day with no documents gets none; verify with TRA
 *       whether a VFD needs a Z report for a day without sales).
 * </ol>
 *
 * A pharmacy whose documents the authority did not issue is recorded with the run (FAILED), so it
 * shows in {@code GET /platform/jobs}. Registered only with a fiscal integration on.
 */
@Component
@ConditionalOnExpression("'${pharmacy.integrations.fiscal.provider:none}' != 'none'")
class FiscalSchedule {

  private static final Logger LOG = LoggerFactory.getLogger(FiscalSchedule.class);
  static final String SERVICE = "fiscal-documents";

  private final TenantService tenants;
  private final FiscalDocuments documents;
  private final SingleRunner runner;
  private final Duration resendAfter;

  FiscalSchedule(
      TenantService tenants,
      FiscalDocuments documents,
      SingleRunner runner,
      @Value("${pharmacy.integrations.fiscal.resend-after:PT10M}") Duration resendAfter) {
    this.tenants = tenants;
    this.documents = documents;
    this.runner = runner;
    this.resendAfter = resendAfter;
  }

  @Scheduled(cron = "${pharmacy.integrations.fiscal.job-cron:0 */15 * * * *}")
  void run() {
    runner.runAlone(SERVICE, this::round);
  }

  /** One round over every active pharmacy (also run by root, through the jobs it can see). */
  void round(JobRun run) {
    int resent = 0;
    int closed = 0;
    for (TenantView tenant : activeTenants()) {
      try {
        int[] done = inTenant(tenant.uid(), () -> pharmacy(tenant, run));
        resent += done[0];
        closed += done[1];
      } catch (RuntimeException failure) {
        LOG.warn("Fiscal documents of {} failed: {}", tenant.code(), failure.getMessage());
        run.failed(tenant.uid(), failure);
      }
    }
    run.note(resent + " documents sent again, " + closed + " branch days closed");
  }

  private int[] pharmacy(TenantView tenant, JobRun run) {
    int resent = documents.resend(resendAfter, 200);
    int closed = 0;
    for (FiscalDocuments.BranchDay day : documents.daysToClose(null)) {
      FiscalDocumentView report = documents.closeDay(day.branchUid(), day.day());
      closed++;
      if (report.status() != FiscalStatus.ISSUED) {
        run.failed(
            tenant.uid(),
            "Z report "
                + report.sourceNumber()
                + " is "
                + report.status()
                + (report.lastError() == null ? "" : ": " + report.lastError()));
      }
    }
    return new int[] {resent, closed};
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
