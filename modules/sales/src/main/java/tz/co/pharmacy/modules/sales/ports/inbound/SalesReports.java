package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads over sales for reports, for the branches the caller may report on. */
public interface SalesReports {

  /**
   * Per cashier: sales, value and the tenders taken by method (in the base currency, cash net of
   * change) between the dates (days in {@code zone}, the pharmacy's time zone), with returns
   * refunded; the till's X/Z figures.
   */
  List<CashierSummary> cashiers(LocalDate from, LocalDate to, java.time.ZoneId zone);

  /** As {@link #cashiers(LocalDate, LocalDate, java.time.ZoneId)} with UTC days. */
  default List<CashierSummary> cashiers(LocalDate from, LocalDate to) {
    return cashiers(from, to, java.time.ZoneOffset.UTC);
  }

  /**
   * Units of each product sold at the branch between the instants, net of units returned; for
   * reorder suggestions. Products with no sales are absent.
   */
  Map<UUID, Integer> unitsSold(UUID branchUid, java.time.Instant from, java.time.Instant to);

  /**
   * Every sale line that took stock from the batch (for a recall), newest first, within the
   * caller's report branches: who bought it and how much is still out (sold less returned).
   */
  List<BatchSale> batchSales(java.util.UUID batchUid);

  record BatchSale(
      java.util.UUID saleUid,
      String saleNumber,
      java.time.Instant soldAt,
      String branchName,
      java.util.UUID customerUid,
      String customerName,
      String patientName,
      int quantity,
      int returnedQuantity) {}

  /**
   * One person's till figures, grouped by account ({@code cashierUid}; rows with no account by
   * email). {@code cashierReference} is the sign-in email they last used in the period; {@code
   * cashier} is the name on their sales, else the reference.
   */
  record CashierSummary(
      String cashier,
      String cashierReference,
      java.util.UUID cashierUid,
      long sales,
      BigDecimal total,
      Map<String, BigDecimal> tenders,
      long returns,
      BigDecimal refunded,
      BigDecimal cashToHandOver,
      BigDecimal cashTendered) {}

  /**
   * The VAT report's split (STD-2): per VAT category, the net (VAT excluded) and the VAT of the
   * pharmacy's sales and of its returns between the days (in {@code zone}), every branch, as the
   * ledger's VAT is. Every category is listed, at zero when nothing was sold under it. A line sold
   * before categories were kept counts under the one its rate implies.
   */
  List<VatCategoryLine> vatByCategory(LocalDate from, LocalDate to, java.time.ZoneId zone);

  /**
   * One VAT category: sales, returns, sales debit notes (STD-2), and what is left ({@code net},
   * {@code tax}: sales less returns plus debit notes).
   */
  record VatCategoryLine(
      tz.co.pharmacy.platform.kernel.TaxCategory category,
      BigDecimal salesNet,
      BigDecimal salesTax,
      BigDecimal returnsNet,
      BigDecimal returnsTax,
      BigDecimal net,
      BigDecimal tax,
      BigDecimal debitNotesNet,
      BigDecimal debitNotesTax) {}
}
