package tz.co.pharmacy.modules.sales.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * {@code sale.return_released}: units a return held in quarantine were released, back to the shelf
 * ({@code outcome} SHELF) or written off (DAMAGED). They were restocked under {@code reference} (a
 * DAMAGED release then wrote them off), so the books move their {@code cost}, held out of inventory
 * since the return, back into it. Keyed by the return line ({@code lineUid}): a line is released
 * once.
 */
public record SaleReturnReleased(
    EventMetadata metadata,
    UUID returnUid,
    UUID lineUid,
    String number,
    UUID branchUid,
    UUID productUid,
    String productName,
    int quantity,
    BigDecimal cost,
    String outcome,
    String reference)
    implements DomainEvent {}
