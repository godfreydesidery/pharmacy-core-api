package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;

/**
 * One payment. {@code amount} is in {@code currency} (null means the base currency), at {@code
 * exchangeRate} base units per unit (null means today's rate; ignored for the base currency).
 * {@code channel} names the registered payment method used (M-Pesa, a bank, a card terminal), for
 * reconciliation; null for none.
 */
public record PaymentCommand(
    PaymentMethod method,
    BigDecimal amount,
    String reference,
    String currency,
    BigDecimal exchangeRate,
    String channel) {

  /** A payment in the base currency. */
  public PaymentCommand(PaymentMethod method, BigDecimal amount, String reference) {
    this(method, amount, reference, null, null, null);
  }

  /** A payment without a named channel. */
  public PaymentCommand(
      PaymentMethod method,
      BigDecimal amount,
      String reference,
      String currency,
      BigDecimal exchangeRate) {
    this(method, amount, reference, currency, exchangeRate, null);
  }
}
