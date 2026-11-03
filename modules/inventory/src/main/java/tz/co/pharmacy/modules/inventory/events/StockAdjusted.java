package tz.co.pharmacy.modules.inventory.events;

import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** "stock.adjusted": a batch's stock was adjusted at a branch. */
public record StockAdjusted(
    EventMetadata metadata,
    UUID productUid,
    String productName,
    UUID batchUid,
    int quantityChange,
    String reasonCode,
    int balanceAfter)
    implements DomainEvent {}
