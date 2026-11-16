package tz.co.pharmacy.modules.purchasing.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** Stock went back to a supplier: {@code total} (base currency) comes off what is owed to it. */
public record SupplierReturned(
    EventMetadata metadata,
    UUID returnUid,
    String number,
    UUID branchUid,
    UUID supplierUid,
    String supplierName,
    BigDecimal total)
    implements DomainEvent {}
