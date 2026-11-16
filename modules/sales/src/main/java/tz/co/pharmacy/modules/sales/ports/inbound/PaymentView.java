package tz.co.pharmacy.modules.sales.ports.inbound;

import java.math.BigDecimal;

/** {@code amount} is in {@code currency}; {@code baseAmount} is its value in the base currency. */
public record PaymentView(
    PaymentMethod method,
    BigDecimal amount,
    String reference,
    String currency,
    BigDecimal exchangeRate,
    BigDecimal baseAmount,
    String channel) {}
