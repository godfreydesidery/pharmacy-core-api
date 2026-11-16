package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Finance's side of each supplier's account, in the base currency: the opening balance owed to them
 * (positive) and payments made (negative, at the rate the payable was booked at).
 */
public interface SupplierAccounts {

  /** An opening balance (OPENING, positive) or a payment (PAYMENT, negative). */
  record Entry(
      UUID supplierUid,
      String supplierName,
      String type,
      UUID documentUid,
      String number,
      String reference,
      LocalDate on,
      BigDecimal amount) {}

  /** One supplier's opening balance and payments, oldest first. */
  List<Entry> entries(UUID supplierUid);

  /** Per supplier: the opening balance, what was paid and the latest date. */
  record Totals(
      UUID supplierUid, String supplierName, BigDecimal opening, BigDecimal paid, LocalDate last) {}

  List<Totals> totals();
}
