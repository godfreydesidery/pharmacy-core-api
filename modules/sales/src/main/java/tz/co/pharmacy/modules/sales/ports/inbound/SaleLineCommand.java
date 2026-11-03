package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One priced line per batch allocation; checkout builds them. {@code serialNumbers} are the units
 * of a serial-tracked product sold on the line; {@code warrantyMonths} the product's warranty.
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
    Integer warrantyMonths) {}
