package tz.co.pharmacy.delivery.api.v1.receivables;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tz.co.pharmacy.delivery.api.Access;
import tz.co.pharmacy.delivery.api.AccessScope;
import tz.co.pharmacy.delivery.api.ApiResponse;
import tz.co.pharmacy.delivery.api.Idempotent;
import tz.co.pharmacy.delivery.api.Pages;
import tz.co.pharmacy.modules.receivables.ports.inbound.AccountView;
import tz.co.pharmacy.modules.receivables.ports.inbound.AgeingRow;
import tz.co.pharmacy.modules.receivables.ports.inbound.CustomerCredit;
import tz.co.pharmacy.modules.receivables.ports.inbound.EntryView;
import tz.co.pharmacy.modules.receivables.ports.inbound.ReceivableTotals;
import tz.co.pharmacy.modules.receivables.ports.inbound.ReceivePaymentCommand;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/** Customer accounts: balances, statements, payments on account, holds, write-offs and ageing. */
@RestController
@RequestMapping("/api/v1/receivables")
@RequiredArgsConstructor
public class ReceivablesHandler {

  private final CustomerCredit credit;
  private final TenantCalendar calendar;

  /** Accounts by customer name; {@code ?withBalance=true} only those that owe or hold credit. */
  @GetMapping("/accounts")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<Page<AccountView>> accounts(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) Boolean withBalance,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    return ApiResponse.ok(
        credit.accounts(q, Boolean.TRUE.equals(withBalance), Pages.of(cursor, limit)),
        "Accounts retrieved");
  }

  /**
   * One account; the POS reads it (with sales.sell) to show the credit available, the accounts page
   * with finance.view. It needed no permission before (ISO-10).
   */
  @GetMapping("/accounts/{customerUid}")
  @Access(
      scope = AccessScope.TENANT,
      anyOf = {"sales.sell", "finance.view"})
  public ApiResponse<AccountView> account(@PathVariable UUID customerUid) {
    return ApiResponse.ok(credit.account(customerUid), "Account retrieved");
  }

  /**
   * One page of the entries dated {@code from} to {@code to} in date order, each with its running
   * balance; {@code openingBalance} (what was owed before {@code from}) precedes them. Without
   * {@code to}, up to the pharmacy's today; without {@code from}, the 90 days up to {@code to}
   * (PERF-17). Pages by {@code cursor} and {@code limit} (default 100).
   */
  @GetMapping("/accounts/{customerUid}/statement")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<Statement> statement(
      @PathVariable UUID customerUid,
      @RequestParam(required = false) LocalDate from,
      @RequestParam(required = false) LocalDate to,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) Integer limit) {
    AccountView account = credit.account(customerUid);
    LocalDate end = to == null ? calendar.today() : to;
    LocalDate start = from == null ? end.minusDays(CustomerCredit.STATEMENT_DAYS - 1L) : from;
    Page<EntryView> entries =
        credit.statement(customerUid, start, end, Pages.of(cursor, limit == null ? 100 : limit));
    return ApiResponse.ok(
        new Statement(account, start, end, credit.openingBalance(customerUid, start), entries),
        "Statement retrieved");
  }

  /** A payment on account, settling open charges FIFO or as chosen; the rest stays as credit. */
  @Idempotent
  @PostMapping("/payments")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "finance.manage")
  public ApiResponse<EntryView> receivePayment(@RequestBody PaymentRequest body) {
    return ApiResponse.created(
        credit.receivePayment(
            new ReceivePaymentCommand(
                Checks.required(body.customerUid(), "customerUid"),
                body.amount(),
                body.method(),
                body.reference(),
                body.date(),
                body.allocateTo() == null ? List.of() : body.allocateTo(),
                body.currency(),
                body.exchangeRate())),
        "Payment received");
  }

  @PostMapping("/accounts/{customerUid}/hold")
  @Access(scope = AccessScope.TENANT, permission = "finance.manage")
  public ApiResponse<AccountView> hold(
      @PathVariable UUID customerUid, @RequestBody HoldRequest body) {
    return ApiResponse.ok(
        credit.hold(customerUid, Boolean.TRUE.equals(body.onHold()), body.reason()),
        Boolean.TRUE.equals(body.onHold()) ? "Account put on hold" : "Account released");
  }

  @Idempotent
  @PostMapping("/write-offs")
  @ResponseStatus(HttpStatus.CREATED)
  @Access(scope = AccessScope.TENANT, permission = "finance.write_off")
  public ApiResponse<EntryView> writeOff(@RequestBody WriteOffRequest body) {
    return ApiResponse.created(
        credit.writeOff(
            Checks.required(body.customerUid(), "customerUid"), body.amount(), body.reason()),
        "Bad debt written off");
  }

  /** Open charges by age since the sale: 0-30, 31-60, 61-90, over 90 days. */
  @GetMapping("/ageing")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<List<AgeingRow>> ageing(@RequestParam(required = false) LocalDate asOf) {
    return ApiResponse.ok(credit.ageing(asOf), "Ageing retrieved");
  }

  @GetMapping("/totals")
  @Access(scope = AccessScope.TENANT, permission = "finance.view")
  public ApiResponse<ReceivableTotals> totals() {
    return ApiResponse.ok(credit.totals(null), "Receivable totals retrieved");
  }

  public record Statement(
      AccountView account,
      LocalDate from,
      LocalDate to,
      BigDecimal openingBalance,
      Page<EntryView> entries) {}

  /** {@code currency} defaults to the base; {@code exchangeRate} to the day's rate. */
  public record PaymentRequest(
      UUID customerUid,
      BigDecimal amount,
      String method,
      String reference,
      LocalDate date,
      List<UUID> allocateTo,
      String currency,
      BigDecimal exchangeRate) {}

  public record HoldRequest(Boolean onHold, String reason) {}

  public record WriteOffRequest(UUID customerUid, BigDecimal amount, String reason) {}
}
