package tz.co.pharmacy.modules.inventory.events;

import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** "stock.low": a product's stock at a branch fell to or below its reorder level. */
public record StockLow(
    EventMetadata metadata, UUID productUid, String productName, int quantity, int reorderLevel)
    implements DomainEvent {}
