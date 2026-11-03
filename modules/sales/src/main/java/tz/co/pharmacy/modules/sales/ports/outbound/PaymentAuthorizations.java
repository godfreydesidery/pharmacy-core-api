package tz.co.pharmacy.modules.sales.ports.outbound;

import java.math.BigDecimal;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;

/**
 * Authorizes every non-cash payment of a sale before it is recorded. This is the integration point
 * for M-Pesa, Airtel Money, card terminals and insurers; the default adapter approves with the
 * reference given. A declined payment refuses the sale with 422 {@code payment_declined}.
 */
public interface PaymentAuthorizations {

  PaymentAuthorization authorize(
      PaymentMethod method, BigDecimal amount, String reference, String saleNumber);

  record PaymentAuthorization(boolean approved, String reference, String declineReason) {}
}
