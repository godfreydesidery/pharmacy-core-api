package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record ReceiptLineView(
    UUID productUid,
    String productName,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    BigDecimal unitCost,
    BigDecimal lineTotal,
    /* {@code unitCost} in the base currency at the receipt's rate: what the stock costs. */
    BigDecimal baseUnitCost,
    List<String> serialNumbers) {}
