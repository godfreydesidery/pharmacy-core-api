package tz.co.pharmacy.modules.receivables.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * One line of an account. {@code amount} is signed in the base currency (debits positive); {@code
 * openAmount} is what is still unpaid of a debit, or still unallocated of a credit. A payment in a
 * foreign currency keeps {@code currency}, {@code foreignAmount} and {@code exchangeRate} (base
 * units per 1 foreign unit). {@code allocations} lists the debits a credit settled, or the credits
 * that settled a debit; {@code runningBalance} is filled on statements.
 */
public record EntryView(
    UUID uid,
    EntryType type,
    String reference,
    UUID saleUid,
    BigDecimal amount,
    BigDecimal openAmount,
    LocalDate date,
    LocalDate dueDate,
    String note,
    Instant createdAt,
    String createdBy,
    String method,
    String currency,
    BigDecimal foreignAmount,
    BigDecimal exchangeRate,
    List<AllocationView> allocations,
    BigDecimal runningBalance) {}
