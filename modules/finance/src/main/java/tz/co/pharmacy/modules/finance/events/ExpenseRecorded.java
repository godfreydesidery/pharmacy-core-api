package tz.co.pharmacy.modules.finance.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/**
 * "expense.recorded". {@code amount} is what was paid; {@code vatAmount} the recoverable input VAT
 * in it (STD-2; null in events from before it was kept: none).
 */
public record ExpenseRecorded(
    EventMetadata metadata,
    UUID expenseUid,
    String number,
    UUID branchUid,
    String category,
    BigDecimal amount,
    LocalDate expenseDate,
    BigDecimal vatAmount)
    implements DomainEvent {}
