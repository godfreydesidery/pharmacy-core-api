package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.StatementKey;

/**
 * Finance's side of each supplier's account, in the base currency: the opening balance owed to them
 * (positive) and payments made (negative, at the rate the payable was booked at).
 */
public interface SupplierAccounts {

  /** An opening balance (OPENING, positive, order 0) or a payment (PAYMENT, negative, order 2). */
  record Entry(
      UUID supplierUid,
      String supplierName,
      String type,
      UUID documentUid,
      String number,
      String reference,
      LocalDate on,
      int order,
      BigDecimal amount) {

    public StatementKey key() {
      return new StatementKey(on, order, number, documentUid);
    }
  }

  /**
   * One supplier's entries of the days [from, to] after {@code after} (null: from the start), in
   * statement order, at most {@code limit}.
   */
  List<Entry> entries(
      UUID supplierUid, LocalDate from, LocalDate to, StatementKey after, int limit);

  /**
   * The sum of one supplier's entries: with {@code through} null, those before day {@code from};
   * otherwise those from day {@code from} up to and including the entry {@code through}.
   */
  BigDecimal sum(UUID supplierUid, LocalDate from, StatementKey through);

  /** Per supplier: the opening balance, what was paid and the latest date. */
  record Totals(
      UUID supplierUid, String supplierName, BigDecimal opening, BigDecimal paid, LocalDate last) {}

  List<Totals> totals();
}
