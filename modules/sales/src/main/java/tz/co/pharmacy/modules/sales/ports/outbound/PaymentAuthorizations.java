package tz.co.pharmacy.modules.sales.ports.outbound;

import java.math.BigDecimal;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;

/**
 * Authorizes every non-cash payment of a sale before it is recorded. This is the integration point
 * for M-Pesa, Airtel Money, card terminals and insurers; the default adapter approves with the
 * reference given. A declined payment refuses the sale with 422 {@code payment_declined}.
 */
public interface PaymentAuthorizations {

  /**
   * {@code channel} is the registered payment method the cashier picked (null for none); a push
   * channel's {@code reference} is the confirmed payment request it was paid through.
   */
  PaymentAuthorization authorize(
      PaymentMethod method, String channel, BigDecimal amount, String reference, String saleNumber);

  record PaymentAuthorization(boolean approved, String reference, String declineReason) {}
}
