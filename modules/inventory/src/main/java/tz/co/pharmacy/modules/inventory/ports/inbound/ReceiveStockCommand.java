package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Stock coming in at the current branch: {@code type} is OPENING or RECEIPT. {@code expiryDate} is
 * required for a product tracked by batch and expiry and ignored otherwise; {@code batchNumber} is
 * optional for a SERIAL or QUANTITY product. A SERIAL product names one serial number per unit.
 */
public record ReceiveStockCommand(
    UUID productUid,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    BigDecimal unitCost,
    MovementType type,
    String reference,
    List<String> serialNumbers) {}
