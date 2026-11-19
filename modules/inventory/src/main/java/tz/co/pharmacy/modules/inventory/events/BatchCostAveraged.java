package tz.co.pharmacy.modules.inventory.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "stock.cost_averaged": a receipt brought more of a batch at a different cost, and the batch now
 * costs the weighted average. {@code roundingValue} is how far the stock's value moved beyond what
 * was paid because the average is kept to the cent (a few cents either way, in the base currency);
 * the books post it so the ledger's inventory keeps matching the stock at cost. {@code movementUid}
 * is the receipt's movement.
 */
public record BatchCostAveraged(
    EventMetadata metadata,
    UUID productUid,
    String productName,
    UUID batchUid,
    String batchNumber,
    BigDecimal previousUnitCost,
    BigDecimal receivedUnitCost,
    BigDecimal averagedUnitCost,
    UUID movementUid,
    BigDecimal roundingValue)
    implements DomainEvent {}
