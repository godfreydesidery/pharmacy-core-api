package tz.co.pharmacy.modules.purchasing.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * Stock went back to a supplier at {@code total} (base currency). {@code awaitingCredit}: the value
 * waits in 1160 for the supplier's credit note (FRD-16; {@link SupplierCredited} then takes it off
 * what is owed); null or false in events from before, which came off what is owed at once.
 */
public record SupplierReturned(
    EventMetadata metadata,
    UUID returnUid,
    String number,
    UUID branchUid,
    UUID supplierUid,
    String supplierName,
    BigDecimal total,
    Boolean awaitingCredit)
    implements DomainEvent {}
