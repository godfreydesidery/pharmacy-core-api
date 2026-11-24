package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What each supplier delivered and took back, in the base currency: goods receipts and the
 * supplier's debit notes (type DEBIT_NOTE, STD-2) raise what is owed to them, returns lower it.
 * Finance adds payments and opening balances. {@code received} counts receipts and debit notes.
 */
public interface SupplierActivity {

  /** A goods receipt (positive amount) or a supplier return (negative amount). */
  record Entry(
      UUID supplierUid,
      String supplierName,
      String type,
      UUID documentUid,
      String number,
      String reference,
      Instant at,
      BigDecimal amount) {}

  /** One supplier's receipts and returns, oldest first. */
  List<Entry> entries(UUID supplierUid);

  /** Per supplier: received and returned totals and the latest activity. */
  record Totals(
      UUID supplierUid,
      String supplierName,
      BigDecimal received,
      BigDecimal returned,
      Instant last) {}

  List<Totals> totals();
}
