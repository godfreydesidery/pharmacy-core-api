package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One batch of a transfer: {@code quantity} dispatched, {@code receivedQuantity} arrived (null
 * while in transit, and on transfers received before receipts were counted line by line).
 */
public record TransferLineView(
    UUID productUid,
    String productName,
    UUID batchUid,
    String batchNumber,
    LocalDate expiryDate,
    int quantity,
    List<String> serialNumbers,
    Integer receivedQuantity) {}
