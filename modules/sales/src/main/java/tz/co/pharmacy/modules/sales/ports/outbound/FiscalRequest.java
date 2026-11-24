package tz.co.pharmacy.modules.sales.ports.outbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import tz.co.pharmacy.modules.sales.ports.inbound.FiscalDocumentKind;
import tz.co.pharmacy.platform.kernel.TaxCategory;

/**
 * What a fiscal document says, as {@link FiscalReceipts} sends it to the tax authority. Amounts are
 * in the base currency (TZS in Tanzania), as booked.
 *
 * @param documentUid the document's own uid: the idempotency key with the authority
 * @param kind RECEIPT, CREDIT_NOTE or Z_REPORT
 * @param number the sale's or return's number, or the Z report's ({@code Z-HQ-20261124})
 * @param branchUid the branch (one fiscal device or VFD registration per branch)
 * @param branchCode the branch's code
 * @param businessDate the pharmacy's local day of the document
 * @param occurredAt when the sale or return was made, or when the day was closed
 * @param seller the pharmacy as registered with the authority
 * @param customerName the customer's name, or null for a walk-in customer
 * @param lines the goods, with their VAT category and amounts (none for a Z report)
 * @param payments how it was paid or refunded (none for a Z report)
 * @param totals the document's totals
 * @param globalCounter the branch's document counter, ever (the VFD's GC)
 * @param dailyCounter the branch's document counter of the business day (the VFD's DC)
 * @param originalReceiptNumber a credit note's: the receipt it corrects
 * @param z a Z report's figures, else null
 */
public record FiscalRequest(
    UUID documentUid,
    FiscalDocumentKind kind,
    String number,
    UUID branchUid,
    String branchCode,
    LocalDate businessDate,
    Instant occurredAt,
    Seller seller,
    String customerName,
    List<Line> lines,
    List<Payment> payments,
    Totals totals,
    long globalCounter,
    Integer dailyCounter,
    String originalReceiptNumber,
    ZReport z) {

  /**
   * The pharmacy as the authority knows it. {@code vatRegistered} false: a trader who issues fiscal
   * receipts without charging VAT; {@code tin} and {@code vrn} may be null when not set.
   */
  public record Seller(String name, String tin, String vrn, boolean vatRegistered) {}

  /**
   * One line: {@code net} excludes VAT, {@code total} = net + tax, after the discount; {@code
   * taxRatePercent} is what was charged (0 for ZERO_RATED and EXEMPT, and for every line of a
   * pharmacy that is not VAT-registered).
   */
  public record Line(
      String description,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal discount,
      TaxCategory taxCategory,
      BigDecimal taxRatePercent,
      BigDecimal net,
      BigDecimal tax,
      BigDecimal total) {}

  /** A payment (or a refund) by method, such as CASH or MOBILE_MONEY, in the base currency. */
  public record Payment(String method, BigDecimal amount) {}

  /** The totals, and the net amount of each VAT category. */
  public record Totals(
      BigDecimal net,
      BigDecimal tax,
      BigDecimal total,
      BigDecimal standardNet,
      BigDecimal zeroRatedNet,
      BigDecimal exemptNet) {}

  /**
   * A Z report's figures: the receipts and credit notes it closes, their counters from {@code
   * firstCounter} to {@code lastCounter} (null when it closes none), and their totals.
   */
  public record ZReport(
      int receipts,
      BigDecimal receiptsTotal,
      BigDecimal receiptsTax,
      int creditNotes,
      BigDecimal creditNotesTotal,
      BigDecimal creditNotesTax,
      Long firstCounter,
      Long lastCounter) {}
}
