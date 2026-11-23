package tz.co.pharmacy.delivery.api.v1.finance;

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
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.finance.ports.inbound.AccountView;
import tz.co.pharmacy.modules.finance.ports.inbound.BalanceMismatch;
import tz.co.pharmacy.modules.finance.ports.inbound.JournalEntryView;
import tz.co.pharmacy.modules.finance.ports.inbound.Ledger;
import tz.co.pharmacy.modules.finance.ports.inbound.LedgerReconciliation;
import tz.co.pharmacy.modules.finance.ports.inbound.UnpostedDocument;
import tz.co.pharmacy.platform.kernel.Page;

/** The chart of accounts with balances, the journal, and documents missing from it. */
@RestController
@RequestMapping("/api/v1")
public class LedgerHandler {

  private final Ledger ledger;
  private final LedgerReconciliation reconciliation;

  public LedgerHandler(Ledger ledger, LedgerReconciliation reconciliation) {
    this.ledger = ledger;
    this.reconciliation = reconciliation;
  }

  /**
   * Source documents of the last {@code days} days (default 7, at most 92) that are more than 15
   * minutes old and still have no journal entry: a posting that failed. Empty when the books are
   * complete.
   */
  @GetMapping("/finance/reconciliation")
  @Access(scope = AccessScope.TENANT_OPTIONAL_BRANCH, permission = "finance.view")
  public ApiResponse<List<UnpostedDocument>> reconciliation(
      @RequestParam(required = false) Integer days) {
    return ApiResponse.ok(
        reconciliation.unposted(days == null ? 7 : days), "Unposted documents retrieved");
  }

  /**
   * The daily account balances (what the balances, trial balance, balance sheet and VAT read)
   * against the journal, for booking days from {@code days} days ago (default 7, at most 366): each
   * account, day and branch where they disagree. Empty when the books agree.
   */
  @GetMapping("/finance/reconciliation/balances")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<List<BalanceMismatch>> balanceReconciliation(
      @RequestParam(required = false) Integer days) {
    return ApiResponse.ok(
        reconciliation.balanceMismatches(days == null ? 7 : days), "Balance mismatches retrieved");
  }

  @GetMapping("/accounts")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<List<AccountView>> accounts() {
    return ApiResponse.ok(ledger.accounts(), "Accounts retrieved");
  }

  @GetMapping("/journal")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<Page<JournalEntryView>> journal(
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        ledger.journal(from, to, Pages.of(cursor, limit)), "Journal entries retrieved");
  }
}
