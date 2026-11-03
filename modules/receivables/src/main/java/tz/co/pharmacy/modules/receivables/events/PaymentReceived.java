package tz.co.pharmacy.modules.receivables.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "receivable.payment_received": {@code amount} is the base amount credited to the account; {@code
 * fxDifference} is the rounding of a foreign payment into the base currency (posted to FX gains and
 * losses).
 */
public record PaymentReceived(
    EventMetadata metadata,
    UUID customerUid,
    BigDecimal amount,
    String method,
    String reference,
    UUID entryUid,
    String currency,
    BigDecimal foreignAmount,
    BigDecimal exchangeRate,
    BigDecimal fxDifference)
    implements DomainEvent {}
