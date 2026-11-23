package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;

/**
 * One payment. {@code amount} is in {@code currency} (null means the base currency), at {@code
 * exchangeRate} base units per unit (null means today's rate; ignored for the base currency).
 * {@code channel} names the registered payment method used (M-Pesa, a bank, a card terminal), for
 * reconciliation; null for none. {@code authorizedAmount} is set by checkout only, when part of a
 * confirmed card or mobile money payment is kept on the customer's account: the provider confirmed
 * that larger amount, and it is what the payment is verified against; null means {@code amount}.
 */
public record PaymentCommand(
    PaymentMethod method,
    BigDecimal amount,
    String reference,
    String currency,
    BigDecimal exchangeRate,
    String channel,
    BigDecimal authorizedAmount) {

  /** A payment as the till sends it. */
  public PaymentCommand(
      PaymentMethod method,
      BigDecimal amount,
      String reference,
      String currency,
      BigDecimal exchangeRate,
      String channel) {
    this(method, amount, reference, currency, exchangeRate, channel, null);
  }

  /** A payment in the base currency. */
  public PaymentCommand(PaymentMethod method, BigDecimal amount, String reference) {
    this(method, amount, reference, null, null, null, null);
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
