package tz.co.pharmacy.modules.inventory.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One entry of the stock ledger; {@code balanceAfter} is the product's stock at the branch after
 * it.
 */
public record MovementView(
    UUID uid,
    UUID productUid,
    String productName,
    UUID batchUid,
    String batchNumber,
    MovementType type,
    int quantityChange,
    int balanceAfter,
    String reference,
    AdjustmentReason reasonCode,
    String note,
    Instant createdAt,
    String createdBy,
    List<String> serialNumbers) {}
