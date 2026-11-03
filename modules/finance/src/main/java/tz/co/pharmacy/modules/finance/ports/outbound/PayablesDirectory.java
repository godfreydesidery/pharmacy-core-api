package tz.co.pharmacy.modules.finance.ports.outbound;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** The goods receipts a supplier payment settles, and suppliers' names (purchasing, partners). */
public interface PayablesDirectory {

  /** A receipt as it was booked: its total in its currency and the rate it was booked at. */
  record BookedReceipt(
      UUID uid,
      String number,
      UUID supplierUid,
      String currency,
      BigDecimal exchangeRate,
      BigDecimal total) {}

  /** The current tenant's receipts with these uids, at any branch; unknown ones are left out. */
  List<BookedReceipt> receipts(Collection<UUID> receiptUids);

  /** 422 supplier_not_found (field supplierUid). */
  String supplierName(UUID supplierUid);
}
