package tz.co.pharmacy.modules.receivables.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Customer credit accounts of the current tenant; all amounts in the base currency. */
public interface CustomerCredit {

  /** A statement without {@code from} covers this many days up to {@code to}, both included. */
  int STATEMENT_DAYS = 90;

  /** The account, created on first use; credit limit and terms come from partners. */
  AccountView account(UUID customerUid);

  /**
   * Refuses 422 account_on_hold, or 422 credit_limit_exceeded when balance + amount exceeds the
   * credit limit. Checkout calls it before a sale with a CREDIT payment.
   */
  void checkCredit(UUID customerUid, BigDecimal amount);

  /** A credit sale's amount on account; due on the sale date plus the customer's terms. */
  EntryView charge(
      UUID customerUid, UUID saleUid, String saleNumber, BigDecimal amount, LocalDate date);

  /**
   * Spends account credit (deposits, overpayments, credit notes) as POS tender ACCOUNT_CREDIT; 422
   * insufficient_account_credit when the account holds less.
   */
  EntryView applyCredit(UUID customerUid, UUID saleUid, String saleNumber, BigDecimal amount);

  /** Allocates FIFO or as given; publishes receivable.payment_received. */
  EntryView receivePayment(ReceivePaymentCommand command);

  /**
   * On a return of a credit sale: settles the sale's open charge first; any remainder stays on the
   * account as credit. {@code refundMethod} is the return's refund method, for the ledger.
   */
  EntryView creditNote(
      UUID customerUid, UUID saleUid, String reference, BigDecimal amount, String refundMethod);

  /** What is still unpaid of the sale's charge, or zero. */
  BigDecimal openForSale(UUID customerUid, UUID saleUid);

  /**
   * A bad debt written off against open charges, oldest first; 422 write_off_exceeds_open. It
   * always needs a second person's approval (CREDIT_WRITE_OFF, amount = the write-off): 403
   * approval_required without one.
   */
  EntryView writeOff(UUID customerUid, BigDecimal amount, String reason);

  /** An account on hold takes no new credit. */
  AccountView hold(UUID customerUid, boolean onHold, String reason);

  /**
   * One page of the entries dated {@code from} to {@code to}, in date order, each with the running
   * balance from the opening balance before {@code from}. Without {@code to}, up to today; without
   * {@code from}, the 90 days up to {@code to}. 422 invalid_period when from is after to.
   */
  Page<EntryView> statement(UUID customerUid, LocalDate from, LocalDate to, PageQuery query);

  /** What the customer owed before {@code before}, from the daily balances (not every entry). */
  BigDecimal openingBalance(UUID customerUid, LocalDate before);

  List<AgeingRow> ageing(LocalDate asOf);

  Page<AccountView> accounts(String q, boolean withBalanceOnly, PageQuery query);

  ReceivableTotals totals(LocalDate asOf);
}
