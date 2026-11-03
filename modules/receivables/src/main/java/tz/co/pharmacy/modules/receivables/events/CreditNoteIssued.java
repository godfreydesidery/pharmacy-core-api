package tz.co.pharmacy.modules.receivables.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "receivable.credit_note_issued": a return's refund kept on account. When the return was refunded
 * by another method, the ledger moves this amount from that method's account to receivables.
 */
public record CreditNoteIssued(
    EventMetadata metadata,
    UUID customerUid,
    UUID entryUid,
    UUID saleUid,
    String reference,
    BigDecimal amount,
    String refundMethod)
    implements DomainEvent {}
