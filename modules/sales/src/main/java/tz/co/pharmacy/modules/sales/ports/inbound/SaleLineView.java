package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * {@code schedule}: the medicine's dispensing class when sold (null for lines from before it was
 * kept); {@code promotionUid}, {@code promotionName}: the promotion that priced the line.
 */
public record SaleLineView(
    UUID uid,
    UUID productUid,
    String productName,
    UUID batchUid,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    int returnedQuantity,
    BigDecimal unitPrice,
    BigDecimal discount,
    BigDecimal tax,
    BigDecimal lineTotal,
    List<String> serialNumbers,
    LocalDate warrantyEndsOn,
    List<String> returnedSerialNumbers,
    String schedule,
    UUID promotionUid,
    String promotionName) {}
