package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One priced line per batch allocation; checkout builds them. {@code serialNumbers} are the units
 * of a serial-tracked product sold on the line; {@code warrantyMonths} the product's warranty.
 * {@code priceIncludesTax}: the unit price already includes VAT (the pharmacy's setting), so the
 * tax is taken out of it rather than added on top. {@code schedule} is the medicine's dispensing
 * class at the moment of sale (OTC, PRESCRIPTION, CONTROLLED); {@code promotionUid} and {@code
 * promotionName} the promotion that priced it, if any. {@code taxCategory} is the product's VAT
 * category (STD-2); {@code taxRatePercent} what is charged: 0 unless STANDARD and the pharmacy is
 * VAT-registered. A null category follows the rate.
 */
public record SaleLineCommand(
    UUID productUid,
    String productName,
    boolean prescriptionRequired,
    UUID batchUid,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal unitCost,
    BigDecimal taxRatePercent,
    List<String> serialNumbers,
    Integer warrantyMonths,
    boolean priceIncludesTax,
    String schedule,
    UUID promotionUid,
    String promotionName,
    tz.co.pharmacy.platform.kernel.TaxCategory taxCategory) {

  /** A line without its VAT category: the rate implies it. */
  public SaleLineCommand(
      UUID productUid,
      String productName,
      boolean prescriptionRequired,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal unitCost,
      BigDecimal taxRatePercent,
      List<String> serialNumbers,
      Integer warrantyMonths,
      boolean priceIncludesTax,
      String schedule,
      UUID promotionUid,
      String promotionName) {
    this(
        productUid,
        productName,
        prescriptionRequired,
        batchUid,
        batchNumber,
        expiryDate,
        quantity,
        unitPrice,
        unitCost,
        taxRatePercent,
        serialNumbers,
        warrantyMonths,
        priceIncludesTax,
        schedule,
        promotionUid,
        promotionName,
        null);
  }

  /** A line without its class or promotion (a quote). */
  public SaleLineCommand(
      UUID productUid,
      String productName,
      boolean prescriptionRequired,
      UUID batchUid,
      String batchNumber,
      LocalDate expiryDate,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal unitCost,
      BigDecimal taxRatePercent,
      List<String> serialNumbers,
      Integer warrantyMonths,
      boolean priceIncludesTax) {
    this(
        productUid,
        productName,
        prescriptionRequired,
        batchUid,
        batchNumber,
        expiryDate,
        quantity,
        unitPrice,
        unitCost,
        taxRatePercent,
        serialNumbers,
        warrantyMonths,
        priceIncludesTax,
        null,
        null,
        null,
        null);
  }
}
