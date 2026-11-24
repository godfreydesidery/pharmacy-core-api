package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.StatementKey;

/**
 * What each supplier delivered and took back, in the base currency: goods receipts raise what is
 * owed to them, credited returns (the supplier's credit note recorded, FRD-16) lower it. Finance
 * adds payments and opening balances. A return still awaiting its credit note changes nothing yet.
 */
public interface SupplierActivity {

  /**
   * A goods receipt (RECEIPT, positive amount, {@code order} 1) or a credited return (RETURN,
   * negative, {@code order} 3), on the pharmacy's local day {@code on}.
   */
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
   * One supplier's entries of the days [from, to] (local days in {@code zone}) after {@code after}
   * (null: from the start), in statement order, at most {@code limit}.
   */
  List<Entry> entries(
      UUID supplierUid, LocalDate from, LocalDate to, ZoneId zone, StatementKey after, int limit);

  /**
   * The sum of one supplier's entries (local days in {@code zone}): with {@code through} null,
   * those before day {@code from} (what a statement opens with); otherwise those from day {@code
   * from} up to and including the entry {@code through} (the balance where a page continues).
   */
  BigDecimal sum(UUID supplierUid, LocalDate from, StatementKey through, ZoneId zone);

  /** Per supplier: received and returned (credited) totals and the latest activity. */
  record Totals(
      UUID supplierUid,
      String supplierName,
      BigDecimal received,
      BigDecimal returned,
      BigDecimal awaitingCredit,
      Instant last) {}

  List<Totals> totals();
}
