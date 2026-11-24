package tz.co.pharmacy.modules.sales.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * {@code sale.debit_note_issued}: the customer of a sale was charged {@code total} more on their
 * account ({@code net} plus {@code tax} VAT, base currency) by a debit note (STD-2).
 */
public record SaleDebitNoteIssued(
    EventMetadata metadata,
    UUID debitNoteUid,
    String number,
    UUID saleUid,
    String saleNumber,
    UUID branchUid,
    UUID customerUid,
    String taxCategory,
    BigDecimal net,
    BigDecimal tax,
    BigDecimal total)
    implements DomainEvent {}
