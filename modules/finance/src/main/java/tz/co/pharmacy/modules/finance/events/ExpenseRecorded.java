package tz.co.pharmacy.modules.finance.events;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.DomainEvent;
import tz.co.pharmacy.platform.kernel.EventMetadata;

/** "expense.recorded" */
public record ExpenseRecorded(
    EventMetadata metadata,
    UUID expenseUid,
    String number,
    UUID branchUid,
    String category,
    BigDecimal amount,
    LocalDate expenseDate)
    implements DomainEvent {}
