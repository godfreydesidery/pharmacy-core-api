package tz.co.pharmacy.delivery.api.v1.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.RequiresFeature;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.finance.ports.inbound.FinancialStatements;
import tz.co.pharmacy.modules.finance.ports.inbound.FinancialStatements.BalanceSheet;
import tz.co.pharmacy.modules.finance.ports.inbound.FinancialStatements.TrialBalance;
import tz.co.pharmacy.modules.finance.ports.inbound.FinancialStatements.VatSummary;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockQueries;
import tz.co.pharmacy.modules.inventory.ports.inbound.StockSummaryView;
import tz.co.pharmacy.modules.sales.ports.inbound.SalesReports;
import tz.co.pharmacy.modules.sales.ports.inbound.SalesReports.CashierSummary;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanFeature;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * Financial statements (trial balance, balance sheet, VAT), stock valuation per branch and the
 * cashier (till) report. Statements cover the whole pharmacy; the others follow the branch rules of
 * the other reports.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@RequiresFeature(PlanFeature.ADVANCED_REPORTS)
public class StatementsHandler {

  private final FinancialStatements statements;
  private final StockQueries stock;
  private final BranchService branches;
  private final SalesReports salesReports;
  private final TenantCalendar calendar;
  private final UserAdministration people;

  /** The pharmacy's "today": report days follow its time zone, not the server's UTC clock. */
  private LocalDate orToday(LocalDate day) {
    return day == null ? calendar.today() : day;
  }

  @GetMapping("/trial-balance")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<TrialBalance> trialBalance(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf) {
    return ApiResponse.ok(statements.trialBalance(orToday(asOf)), "Trial balance retrieved");
  }

  @GetMapping("/balance-sheet")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<BalanceSheet> balanceSheet(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf) {
    return ApiResponse.ok(statements.balanceSheet(orToday(asOf)), "Balance sheet retrieved");
  }

  @GetMapping("/vat")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<VatSummary> vat(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return ApiResponse.ok(
        statements.vat(from == null ? orToday(null).withDayOfMonth(1) : from, orToday(to)),
        "VAT summary retrieved");
  }

  /** Stock at cost and at retail for one branch. */
  public record BranchStock(
      UUID branchUid,
      String branchName,
      long products,
      long expired,
      BigDecimal costValue,
      BigDecimal retailValue) {}

  @GetMapping("/stock-valuation")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "reports.view")
  public ApiResponse<List<BranchStock>> stockValuation(
      @RequestParam(required = false) Integer expiryAlertDays) {
    Set<UUID> allowed = ExecutionContext.reportBranches();
    int days = expiryAlertDays == null ? 90 : expiryAlertDays;
    return ApiResponse.ok(
        branches.list().stream()
            .filter(b -> allowed == null || allowed.contains(b.uid()))
            .map(
                b -> {
                  StockSummaryView s = stock.summary(Set.of(b.uid()), days);
                  return new BranchStock(
                      b.uid(), b.name(), s.products(), s.expired(), s.costValue(), s.retailValue());
                })
            .toList(),
        "Stock valuation retrieved");
  }

  @GetMapping("/cashiers")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "reports.view")
  public ApiResponse<List<CashierSummary>> cashiers(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    List<CashierSummary> rows = salesReports.cashiers(from, to, calendar.zone());
    // One row per person, named as their account is now (refunds carry no name of their own);
    // rows from before accounts were recorded are named by their email. Someone who has left the
    // pharmacy is named as it kept them when they left, else as the sale recorded them, never by
    // what they changed since (adversarial review ISO-6).
    Map<java.util.UUID, String> byUid =
        people.displayNamesByUid(rows.stream().map(CashierSummary::cashierUid).toList());
    Map<String, String> byEmail =
        people.displayNames(
            rows.stream()
                .filter(r -> r.cashierUid() == null)
                .map(CashierSummary::cashierReference)
                .toList());
    return ApiResponse.ok(
        rows.stream()
            .map(
                r -> {
                  String name =
                      r.cashierUid() != null
                          ? byUid.get(r.cashierUid())
                          : byEmail.get(r.cashierReference());
                  return name != null
                      ? new CashierSummary(
                          name,
                          r.cashierReference(),
                          r.cashierUid(),
                          r.sales(),
                          r.total(),
                          r.tenders(),
                          r.returns(),
                          r.refunded(),
                          r.cashToHandOver(),
                          r.cashTendered())
                      : r;
                })
            .sorted(java.util.Comparator.comparing(CashierSummary::cashier))
            .toList(),
        "Cashier report retrieved");
  }
}
