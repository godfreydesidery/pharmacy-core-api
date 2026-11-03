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
import tz.co.pharmacy.modules.finance.ports.inbound.JournalEntryView;
import tz.co.pharmacy.modules.finance.ports.inbound.Ledger;
import tz.co.pharmacy.platform.kernel.Page;

/** The chart of accounts with balances, and the journal. */
@RestController
@RequestMapping("/api/v1")
public class LedgerHandler {

  private final Ledger ledger;

  public LedgerHandler(Ledger ledger) {
    this.ledger = ledger;
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
