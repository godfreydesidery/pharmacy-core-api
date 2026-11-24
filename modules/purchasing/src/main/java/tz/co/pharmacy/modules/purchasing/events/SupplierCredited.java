package tz.co.pharmacy.modules.purchasing.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "supplier.credited": the supplier's credit note for a return was recorded (FRD-16). {@code
 * returnValue} (the return's cost, in base) leaves 1160 awaiting credit; {@code creditBase} (the
 * credit note in base at the goods receipt's booked rate) comes off what is owed; the difference is
 * a stock loss or gain.
 */
public record SupplierCredited(
    EventMetadata metadata,
    UUID returnUid,
    String number,
    UUID branchUid,
    UUID supplierUid,
    String creditNoteNumber,
    BigDecimal returnValue,
    BigDecimal creditBase,
    String currency,
    BigDecimal creditAmount)
    implements DomainEvent {}
