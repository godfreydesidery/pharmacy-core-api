package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExpenseCommand(
    ExpenseCategory category,
    String description,
    BigDecimal amount,
    LocalDate expenseDate,
    String paymentMethod,
    String reference) {}
