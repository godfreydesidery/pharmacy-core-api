package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A sales debit note (STD-2). {@code fiscalStatus} null when no fiscal provider is on. */
public record DebitNoteView(
    UUID uid,
    String number,
    UUID branchUid,
    UUID saleUid,
    String saleNumber,
    UUID customerUid,
    String reason,
    String taxCategory,
    BigDecimal taxRatePercent,
    BigDecimal net,
    BigDecimal tax,
    BigDecimal total,
    String fiscalStatus,
    String fiscalReference,
    Instant createdAt) {}
