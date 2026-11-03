package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.outbound.PaymentAuthorizations;

/**
 * The default payment integration, {@code pharmacy.integrations.payments.provider=manual}: the
 * cashier took the card, mobile money or insurance payment on a separate device, so it is approved
 * with the reference the cashier typed, or one generated like {@code MAN-1a2b3c4d}.
 *
 * <p>This is the plug point for real providers. An M-Pesa, Airtel Money, card terminal or insurer
 * adapter implements {@link PaymentAuthorizations}, is enabled by its own provider value, such as
 * {@code @ConditionalOnProperty(name = "pharmacy.integrations.payments.provider", havingValue =
 * "mpesa")}, and replaces this one.
 */
@Component
@ConditionalOnProperty(
    name = "pharmacy.integrations.payments.provider",
    havingValue = "manual",
    matchIfMissing = true)
class ManualPaymentAuthorizations implements PaymentAuthorizations {

  @Override
  public PaymentAuthorization authorize(
      PaymentMethod method, BigDecimal amount, String reference, String saleNumber) {
    String approved =
        reference == null || reference.isBlank()
            ? "MAN-" + UUID.randomUUID().toString().substring(0, 8)
            : reference;
    return new PaymentAuthorization(true, approved, null);
  }
}
