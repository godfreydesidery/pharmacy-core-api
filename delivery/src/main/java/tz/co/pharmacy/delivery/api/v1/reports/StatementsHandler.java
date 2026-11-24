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
  private final tz.co.pharmacy.modules.purchasing.ports.inbound.SupplierDebitNotes
      supplierDebitNotes;
  private final tz.co.pharmacy.modules.finance.ports.inbound.ExpiryProvisions provisions;
  private final tz.co.pharmacy.modules.finance.ports.inbound.VatApportionments apportionments;

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

  /**
   * The VAT return's figures for the period (STD-2), for the whole pharmacy: from the ledger, the
   * output VAT on sales (2100 credits), the VAT given back on returns, the input VAT on purchases
   * and expenses (1150) and the net payable to TRA; from the sales, the net and VAT of the sales
   * and returns split by VAT category (standard, zero-rated, exempt).
   */
  @GetMapping("/vat")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<VatReport> vat(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    ReportRange range =
        ReportRange.limited(from == null ? orToday(null).withDayOfMonth(1) : from, orToday(to));
    VatSummary summary = statements.vat(range.from(), range.to());
    List<SalesReports.VatCategoryLine> categories =
        salesReports.vatByCategory(range.from(), range.to(), calendar.zone());
    BigDecimal salesNet = BigDecimal.ZERO;
    BigDecimal salesTax = BigDecimal.ZERO;
    for (SalesReports.VatCategoryLine line : categories) {
      salesNet = salesNet.add(line.debitNotesNet());
      salesTax = salesTax.add(line.debitNotesTax());
    }
    var purchases =
        supplierDebitNotes.totals(
            range.from().atStartOfDay(calendar.zone()).toInstant(),
            range.to().plusDays(1).atStartOfDay(calendar.zone()).toInstant());
    return ApiResponse.ok(
        new VatReport(
            summary.from(),
            summary.to(),
            summary.outputVat(),
            summary.vatReversed(),
            summary.netOutputVat(),
            summary.inputVat(),
            summary.netPayable(),
            categories,
            summary.inputVatBeforeApportionment(),
            summary.nonReclaimableInputVat(),
            summary.inputVat(),
            apportionments.runs(range.from(), range.to()),
            new DebitNoteTotals(salesNet, salesTax, purchases.net(), purchases.vat())),
        "VAT summary retrieved");
  }

  /**
   * The expiry write-down runs (STD-3) whose month falls in the range, newest first: the rule each
   * used, the stock at cost and the provision by branch and band, and the journal entry.
   */
  @GetMapping("/expiry-provision")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<
          List<tz.co.pharmacy.modules.finance.ports.inbound.ExpiryProvisions.ExpiryProvisionRun>>
      expiryProvision(
          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
              LocalDate from,
          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
              LocalDate to) {
    LocalDate end = orToday(to);
    return ApiResponse.ok(
        provisions.list(from == null ? end.minusMonths(12).withDayOfMonth(1) : from, end),
        "Expiry write-down retrieved");
  }

  /** The VAT apportionment runs (STD-2) whose month falls in the range, newest first. */
  @GetMapping("/vat-apportionment")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<
          List<tz.co.pharmacy.modules.finance.ports.inbound.VatApportionments.VatApportionmentRun>>
      vatApportionment(
          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
              LocalDate from,
          @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
              LocalDate to) {
    LocalDate end = orToday(to);
    return ApiResponse.ok(
        apportionments.runs(from == null ? end.minusMonths(12).withDayOfMonth(1) : from, end),
        "VAT apportionment retrieved");
  }

  /** Debit notes of the period (STD-2): on sales (output VAT) and from suppliers (input VAT). */
  public record DebitNoteTotals(
      BigDecimal salesNet, BigDecimal salesTax, BigDecimal purchasesNet, BigDecimal purchasesVat) {}

  /**
   * The VAT report: {@code netPayable} = {@code outputVat} − {@code vatReversed} − {@code inputVat}
   * (negative: a credit). {@code categories} splits the period's sales and returns by VAT category.
   */
  public record VatReport(
      LocalDate from,
      LocalDate to,
      BigDecimal outputVat,
      BigDecimal vatReversed,
      BigDecimal netOutputVat,
      BigDecimal inputVat,
      BigDecimal netPayable,
      List<SalesReports.VatCategoryLine> categories,
      /* STD-2 partial exemption: input VAT before the apportionment, what it moved to 5150 (not
       * reclaimable), and what is reclaimable (= inputVat). */
      BigDecimal inputVatBeforeApportionment,
      BigDecimal nonReclaimableInputVat,
      BigDecimal reclaimableInputVat,
      List<tz.co.pharmacy.modules.finance.ports.inbound.VatApportionments.VatApportionmentRun>
          apportionments,
      DebitNoteTotals debitNotes) {}

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
    // From a given day (default today) to a given day (default the same day), at most 366 days.
    LocalDate start = orToday(from);
    ReportRange.limited(start, to == null ? start : to);
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
