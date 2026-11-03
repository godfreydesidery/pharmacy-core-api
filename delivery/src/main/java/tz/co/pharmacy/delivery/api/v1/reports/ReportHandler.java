package tz.co.pharmacy.delivery.api.v1.reports;

import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.modules.reporting.ports.inbound.DashboardView;
import tz.co.pharmacy.modules.reporting.ports.inbound.PaymentMix;
import tz.co.pharmacy.modules.reporting.ports.inbound.ProductReportRow;
import tz.co.pharmacy.modules.reporting.ports.inbound.ProfitLoss;
import tz.co.pharmacy.modules.reporting.ports.inbound.Reports;
import tz.co.pharmacy.modules.reporting.ports.inbound.SalesReport;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The dashboard and the reports. Without X-Branch-Id they cover every branch the caller may use;
 * with it, that branch only.
 */
@RestController
@RequestMapping("/api/v1")
public class ReportHandler {

  private final Reports reports;

  public ReportHandler(Reports reports) {
    this.reports = reports;
  }

  @GetMapping("/dashboard")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "dashboard.view")
  public ApiResponse<DashboardView> dashboard(@RequestParam(required = false) Integer days) {
    int period = days == null ? 30 : days;
    if (period < 7 || period > 365) {
      throw Refusal.invalid("invalid_days", "days must be between 7 and 365.", "days");
    }
    return ApiResponse.ok(reports.dashboard(period), "Dashboard retrieved");
  }

  @GetMapping("/reports/sales")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "reports.view")
  public ApiResponse<SalesReport> sales(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    ReportRange range = ReportRange.of(from, to);
    return ApiResponse.ok(reports.sales(range.from(), range.to()), "Sales report retrieved");
  }

  @GetMapping("/reports/products")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "reports.view")
  public ApiResponse<List<ProductReportRow>> products(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer limit) {
    ReportRange range = ReportRange.of(from, to);
    int rows = limit == null ? 20 : limit;
    if (rows < 1 || rows > 100) {
      throw Refusal.invalid("invalid_limit", "limit must be between 1 and 100.", "limit");
    }
    return ApiResponse.ok(
        reports.products(range.from(), range.to(), rows), "Product report retrieved");
  }

  @GetMapping("/reports/profit-loss")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "reports.view")
  public ApiResponse<ProfitLoss> profitLoss(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    ReportRange range = ReportRange.of(from, to);
    return ApiResponse.ok(
        reports.profitLoss(range.from(), range.to()), "Profit and loss retrieved");
  }

  @GetMapping("/reports/payments")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "reports.view")
  public ApiResponse<List<PaymentMix>> payments(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    ReportRange range = ReportRange.of(from, to);
    return ApiResponse.ok(reports.payments(range.from(), range.to()), "Payment report retrieved");
  }
}
