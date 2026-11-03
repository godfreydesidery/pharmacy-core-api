package tz.co.pharmacy.modules.reporting.ports.inbound;

import java.time.LocalDate;
import java.util.List;

/**
 * The dashboard and the reports, over {@code ExecutionContext.reportBranches()}: the request's
 * branch, or every branch the caller may use. Dates are local dates in the tenant's timezone.
 */
public interface Reports {

  /** The last {@code days} days, today included, against the {@code days} days before. */
  DashboardView dashboard(int days);

  SalesReport sales(LocalDate from, LocalDate to);

  List<ProductReportRow> products(LocalDate from, LocalDate to, int limit);

  ProfitLoss profitLoss(LocalDate from, LocalDate to);

  List<PaymentMix> payments(LocalDate from, LocalDate to);
}
