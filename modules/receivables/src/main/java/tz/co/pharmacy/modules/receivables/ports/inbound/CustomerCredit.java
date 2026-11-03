package tz.co.pharmacy.modules.receivables.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

/** Customer credit accounts of the current tenant; all amounts in the base currency. */
public interface CustomerCredit {

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

  /** A bad debt written off against open charges, oldest first; 422 write_off_exceeds_open. */
  EntryView writeOff(UUID customerUid, BigDecimal amount, String reason);

  /** An account on hold takes no new credit. */
  AccountView hold(UUID customerUid, boolean onHold, String reason);

  /** Entries in date order with a running balance from the opening balance before {@code from}. */
  Page<EntryView> statement(UUID customerUid, LocalDate from, LocalDate to, PageQuery query);

  BigDecimal openingBalance(UUID customerUid, LocalDate before);

  List<AgeingRow> ageing(LocalDate asOf);

  Page<AccountView> accounts(String q, boolean withBalanceOnly, PageQuery query);

  ReceivableTotals totals(LocalDate asOf);
}
