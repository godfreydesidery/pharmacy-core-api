package tz.co.pharmacy.platform.composition;

import static java.util.stream.Collectors.joining;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.integrations.ports.inbound.Payments;
import tz.co.pharmacy.modules.sales.ports.inbound.PaymentMethod;
import tz.co.pharmacy.modules.sales.ports.outbound.PaymentAuthorizations;

/**
 * Binds the till's payment approval to the payment registry. A tender is verified through the
 * registered method (its channel) it was taken with: a push request must be confirmed and unused, a
 * method that needs a reference must have one, a method the pharmacy does not accept is declined,
 * and the method must be of the tender's kind (an M-Pesa channel cannot book a card payment).
 *
 * <p>A tender sent without a channel takes the pharmacy's only method of that kind; when it has
 * several (M-Pesa, Mixx, Airtel...) the till must say which, so the money can be matched to that
 * account later. Only a pharmacy with no method of that kind registered keeps the old behaviour:
 * the reference typed, or one generated like {@code MAN-1a2b3c4d}.
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
    List<Payments.TenantMethodView> ofKind =
        payments.tillMethods().stream().filter(m -> m.kind().equals(method.name())).toList();
    String code = channel == null || channel.isBlank() ? null : channel.trim().toUpperCase();
    if (code == null) {
      if (ofKind.isEmpty()) {
        return new PaymentAuthorization(
            true, reference == null || reference.isBlank() ? generated() : reference, null);
      }
      if (ofKind.size() > 1) {
        return new PaymentAuthorization(
            false,
            null,
            "Say which "
                + label(method)
                + " method was used ("
                + ofKind.stream().map(Payments.TenantMethodView::code).collect(joining(", "))
                + "), so the payment can be checked.");
      }
      code = ofKind.getFirst().code();
    } else {
      String chosen = code;
      boolean sameKind = ofKind.stream().anyMatch(m -> m.code().equals(chosen));
      boolean registered = payments.tillMethods().stream().anyMatch(m -> m.code().equals(chosen));
      if (registered && !sameKind) {
        return new PaymentAuthorization(
            false, null, chosen + " is not a " + label(method) + " method.");
      }
    }
    Payments.Verification verification = payments.verify(code, amount, reference, saleNumber);
    return new PaymentAuthorization(
        verification.approved(),
        verification.reference() == null || verification.reference().isBlank()
            ? generated()
            : verification.reference(),
        verification.declineReason());
  }

  /**
   * A reference that names a push request: asks the provider now, in the request's own short
   * transaction ({@link Payments#paymentRequest}), and refuses while the customer has not paid. A
   * request of another branch, an unknown one or a typed code is left to {@link #authorize}.
   */
  @Override
  public java.util.Optional<String> prepare(
      PaymentMethod method, String channel, String reference) {
    UUID request;
    try {
      request = UUID.fromString(reference.trim());
    } catch (IllegalArgumentException notARequest) {
      return java.util.Optional.empty();
    }
    Payments.IntentView intent;
    try {
      intent = payments.paymentRequest(request);
    } catch (tz.co.pharmacy.platform.kernel.Refusal notHere) {
      return java.util.Optional.empty();
    }
    return "PENDING".equals(intent.status())
        ? java.util.Optional.of(
            "The " + label(method) + " request is still waiting for the customer to pay.")
        : java.util.Optional.empty();
  }

  private static String label(PaymentMethod method) {
    return method.name().toLowerCase(Locale.ROOT).replace('_', ' ');
  }

  private static String generated() {
    return "MAN-" + UUID.randomUUID().toString().substring(0, 8);
  }
}
