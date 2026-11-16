package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.outbound.PaymentAuthorizations;

/**
 * Binds the till's payment approval to the payment registry. A tender through a registered method
 * (its channel) is verified there: a push request must be confirmed and unused, a method that needs
 * a reference must have one, and a method the pharmacy does not accept is declined. A tender
 * without a channel is approved with the reference typed, or one generated like {@code
 * MAN-1a2b3c4d} (the cashier took it on a separate device).
 */
@Component
@ConditionalOnProperty(
    name = "pharmacy.integrations.payments.provider",
    havingValue = "manual",
    matchIfMissing = true)
class ManualPaymentAuthorizations implements PaymentAuthorizations {

  private final Payments payments;

  ManualPaymentAuthorizations(Payments payments) {
    this.payments = payments;
  }

  @Override
  public PaymentAuthorization authorize(
      PaymentMethod method,
      String channel,
      BigDecimal amount,
      String reference,
      String saleNumber) {
    if (channel != null && !channel.isBlank()) {
      Payments.Verification verification = payments.verify(channel, amount, reference, saleNumber);
      return new PaymentAuthorization(
          verification.approved(),
          verification.reference() == null || verification.reference().isBlank()
              ? generated()
              : verification.reference(),
          verification.declineReason());
    }
    return new PaymentAuthorization(
        true, reference == null || reference.isBlank() ? generated() : reference, null);
  }

  private static String generated() {
    return "MAN-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
