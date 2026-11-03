package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;

/**
 * One payment. {@code amount} is in {@code currency} (null means the base currency), at {@code
 * exchangeRate} base units per unit (null means today's rate; ignored for the base currency).
 */
public record PaymentCommand(
    PaymentMethod method,
    BigDecimal amount,
    String reference,
    String currency,
    BigDecimal exchangeRate) {

  /** A payment in the base currency. */
  public PaymentCommand(PaymentMethod method, BigDecimal amount, String reference) {
    this(method, amount, reference, null, null);
  }
}
