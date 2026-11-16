package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Payment of a subscription invoice. The root administrator records one by hand today; a payment
 * adapter (mobile money, card) calls the same port once its payment is confirmed.
 */
public interface SubscriptionPayments {

  /**
   * Marks the invoice paid: the subscription becomes ACTIVE for the invoiced period, and a tenant
   * suspended for non-payment is reactivated. Refuses 422 invoice_not_open; {@code amount} null
   * means the invoice amount, and a smaller one is refused 422 payment_short.
   */
  Subscriptions.InvoiceView recordPayment(
      UUID invoiceUid, BigDecimal amount, String method, String reference, LocalDate paidOn);
}
