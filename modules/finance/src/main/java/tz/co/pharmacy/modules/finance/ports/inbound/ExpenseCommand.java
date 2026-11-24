package tz.co.pharmacy.modules.finance.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * An expense. {@code amount} is what was paid; {@code vatAmount} (STD-2) the input VAT in it, from
 * the supplier's VAT invoice, recoverable only by a VAT-registered pharmacy (null or 0 for none).
 */
public record ExpenseCommand(
    ExpenseCategory category,
    String description,
    BigDecimal amount,
    LocalDate expenseDate,
    String paymentMethod,
    String reference,
    BigDecimal vatAmount) {

  /** An expense without VAT. */
  public ExpenseCommand(
      ExpenseCategory category,
      String description,
      BigDecimal amount,
      LocalDate expenseDate,
      String paymentMethod,
      String reference) {
    this(category, description, amount, expenseDate, paymentMethod, reference, null);
  }
}
