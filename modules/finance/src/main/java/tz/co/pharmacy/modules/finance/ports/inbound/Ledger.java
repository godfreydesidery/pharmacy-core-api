package tz.co.pharmacy.modules.finance.ports.inbound;

import java.time.LocalDate;
import java.util.List;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;

public interface Ledger {

  /** The chart of accounts with balances; seeds the default chart on first use (idempotent). */
  List<AccountView> accounts();

  Page<JournalEntryView> journal(LocalDate from, LocalDate to, PageQuery query);

  /** Idempotent per (sourceType, sourceUid): a redelivered event posts nothing new. */
  void postSale(SalePosting posting);

  void postReturn(ReturnPosting posting);

  void postReceipt(ReceiptPosting posting);

  /** Whether anything has been posted to the current tenant's books. */
  boolean hasEntries();

  /**
   * Posts the opening balances once per tenant: Dr cash, bank, mobile money, inventory and
   * receivables / Cr payables, with owner's equity (3000) taking the difference. 409
   * opening_balances_posted when already done; 422 invalid_amount for a negative figure.
   */
  OpeningBalanceView postOpening(OpeningBalancesCommand command);

  java.util.Optional<OpeningBalanceView> opening();

  /** One account's balance on its natural side (an asset debit-positive), 0 when unused. */
  java.math.BigDecimal balance(String accountCode);

  /**
   * Stock returned to a supplier at cost (a debit note): Dr 2000 accounts payable / Cr 1200
   * inventory; idempotent per return.
   */
  void postSupplierReturn(
      java.util.UUID returnUid,
      String number,
      java.util.UUID branchUid,
      java.time.LocalDate date,
      java.math.BigDecimal value);

  /**
   * A stock adjustment (damage, loss, expiry, count correction, destruction) at cost: Dr 5100 / Cr
   * 1200 for a loss, the reverse for a gain; idempotent per stock movement.
   */
  void postStockAdjustment(
      java.util.UUID movementUid,
      String reference,
      java.util.UUID branchUid,
      java.time.LocalDate date,
      java.math.BigDecimal value);

  /** A voucher sold: Dr the account paid into / Cr 2300; idempotent per voucher. */
  void postVoucherIssued(
      java.util.UUID voucherUid,
      String code,
      java.util.UUID branchUid,
      java.time.LocalDate date,
      java.math.BigDecimal value,
      String paidBy);
}
