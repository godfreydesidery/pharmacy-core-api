package tz.co.pharmacy.modules.receivables.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** "receivable.written_off": a bad debt, posted to bad debts expense. */
public record DebtWrittenOff(
    EventMetadata metadata,
    UUID customerUid,
    UUID entryUid,
    String reference,
    BigDecimal amount,
    String reason)
    implements DomainEvent {}
