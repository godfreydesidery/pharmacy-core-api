package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ExpenseView(
    UUID uid,
    String number,
    UUID branchUid,
    ExpenseCategory category,
    String description,
    BigDecimal amount,
    LocalDate expenseDate,
    String paymentMethod,
    String reference,
    Instant createdAt,
    String createdBy,
    /* The input VAT in the amount (STD-2), 0 for none. */
    BigDecimal vatAmount) {}
