package tz.co.pharmacy.modules.purchasing.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One product received. {@code expiryDate} is required for a product tracked by batch and expiry
 * only; {@code batchNumber} is optional for SERIAL and QUANTITY products; a SERIAL product names
 * one serial number per unit.
 */
public record ReceiptLineCommand(
    UUID productUid,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    BigDecimal unitCost,
    List<String> serialNumbers) {}
