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
}
