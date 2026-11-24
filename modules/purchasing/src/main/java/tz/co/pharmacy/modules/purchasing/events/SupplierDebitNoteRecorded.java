package tz.co.pharmacy.modules.purchasing.events;

import java.math.BigDecimal;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * {@code supplier.debit_note_recorded}: a supplier's debit note raised what is owed for a goods
 * receipt by {@code total} ({@code net} plus {@code vat} input VAT), base currency (STD-2).
 */
public record SupplierDebitNoteRecorded(
    EventMetadata metadata,
    UUID debitNoteUid,
    String number,
    UUID goodsReceiptUid,
    UUID branchUid,
    UUID supplierUid,
    BigDecimal net,
    BigDecimal vat,
    BigDecimal total)
    implements DomainEvent {}
