package tz.co.pharmacy.modules.inventory.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "stock.adjusted": a batch's stock was adjusted at a branch. {@code value} is the change at the
 * batch's unit cost (negative for a loss), in the base currency; {@code movementUid} identifies it.
 */
public record StockAdjusted(
    EventMetadata metadata,
    UUID productUid,
    String productName,
    UUID batchUid,
    int quantityChange,
    String reasonCode,
    int balanceAfter,
    UUID movementUid,
    BigDecimal value)
    implements DomainEvent {}
