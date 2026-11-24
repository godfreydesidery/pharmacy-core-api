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

  /**
   * As {@link #authorize(PaymentMethod, String, BigDecimal, String, String)}, for the sale whose
   * idempotency key is {@code saleKey} (null when it was sent without one): a push request bound to
   * another sale is refused 422 {@code payment_mismatch} (WEB-9). An adapter that binds no request
   * to a sale keeps the five-argument form.
   */
  default PaymentAuthorization authorize(
      PaymentMethod method,
      String channel,
      BigDecimal amount,
      String reference,
      String saleNumber,
      String saleKey) {
    return authorize(method, channel, amount, reference, saleNumber);
  }

  /**
   * Runs before the sale's transaction, outside any (PERF-1): asks the provider about a push
   * request it has not confirmed yet, so that {@link #authorize}, inside the transaction that takes
   * the sale number and the stock, makes no call out. Returns why the tender cannot be approved now
   * (the request is still waiting for the customer), or empty to go on to {@link #authorize}, which
   * decides.
   */
  default java.util.Optional<String> prepare(
      PaymentMethod method, String channel, String reference) {
    return java.util.Optional.empty();
  }

  record PaymentAuthorization(boolean approved, String reference, String declineReason) {}
}
