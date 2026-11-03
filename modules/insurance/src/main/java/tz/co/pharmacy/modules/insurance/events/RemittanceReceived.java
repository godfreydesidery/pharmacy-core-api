package tz.co.pharmacy.modules.insurance.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** "insurance.remittance_received". */
public record RemittanceReceived(
    EventMetadata metadata,
    UUID payerUid,
    BigDecimal amount,
    String reference,
    UUID remittanceUid,
    String number,
    java.time.LocalDate date)
    implements DomainEvent {}
