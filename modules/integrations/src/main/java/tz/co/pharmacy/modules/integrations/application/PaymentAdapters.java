package tz.co.pharmacy.modules.integrations.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The adapters that push a payment request to a customer's phone and report its outcome, by the
 * registry's adapter code. SIMULATOR confirms every request, for training and tests. The provider
 * adapters (M-Pesa, Mixx by Yas, Airtel Money, Selcom, AzamPay) are the plug points for each
 * provider's API: until one is connected with merchant credentials and tested against the
 * provider's sandbox it reports itself unavailable, and the cashier records the provider's
 * confirmation code by hand, which the till accepts as the reference.
 *
 * <p>With {@code pharmacy.integrations.payments.simulator-outcomes=by-phone} (the simulated world
 * sets it) the simulator answers like a provider would, chosen by the last two digits of the phone
 * so a run is repeatable: 01 the customer declines, 02 insufficient funds, 03 no answer (the
 * request stays pending until it expires); any other number confirms.
 */
@Component
class PaymentAdapters {

  /** What an adapter answers when asked to push or to report. */
  record Outcome(String status, String providerReference, String failureReason) {

    static Outcome pending(String reference) {
      return new Outcome("PENDING", reference, null);
    }

    static Outcome confirmed(String reference) {
      return new Outcome("CONFIRMED", reference, null);
    }

    static Outcome failed(String reference, String reason) {
      return new Outcome("FAILED", reference, reason);
    }
  }

  /** One provider's push flow. */
  interface PaymentAdapter {

    /** Whether requests can be pushed through this adapter now. */
    boolean available();

    Outcome push(String phone, BigDecimal amount, String currency, String accountReference);

    Outcome status(String providerReference);
  }

  private final Map<String, PaymentAdapter> adapters;

  PaymentAdapters(
      @Value("${pharmacy.integrations.payments.simulator-outcomes:confirm}")
          String simulatorOutcomes) {
    PaymentAdapter unavailable =
        new PaymentAdapter() {
          @Override
          public boolean available() {
            return false;
          }

          @Override
          public Outcome push(
              String phone, BigDecimal amount, String currency, String accountReference) {
            throw new IllegalStateException("not connected");
          }

          @Override
          public Outcome status(String providerReference) {
            throw new IllegalStateException("not connected");
          }
        };
    PaymentAdapter simulator = new Simulator("by-phone".equalsIgnoreCase(simulatorOutcomes));
    this.adapters =
        Map.of(
            "SIMULATOR", simulator,
            "MPESA", unavailable,
            "MIXX", unavailable,
            "AIRTEL", unavailable,
            "SELCOM", unavailable,
            "AZAMPAY", unavailable);
  }

  /** The adapter for a registry code, or null for methods without push (NONE, MANUAL). */
  PaymentAdapter of(String adapterCode) {
    return adapters.get(adapterCode);
  }

  /**
   * The SIMULATOR adapter. The outcome travels in the reference's last letter (A approved, D
   * declined, F insufficient funds, T no answer), so a status check needs no memory of the push.
   */
  static final class Simulator implements PaymentAdapter {

    private final boolean byPhone;

    Simulator(boolean byPhone) {
      this.byPhone = byPhone;
    }

    @Override
    public boolean available() {
      return true;
    }

    @Override
    public Outcome push(String phone, BigDecimal amount, String currency, String accountReference) {
      String outcome = byPhone ? outcomeFor(phone) : "";
      return Outcome.pending(
          "SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase() + outcome);
    }

    @Override
    public Outcome status(String providerReference) {
      String reference = providerReference == null ? "" : providerReference;
      if (!byPhone || reference.length() != 13) {
        return Outcome.confirmed(reference);
      }
      return switch (reference.charAt(reference.length() - 1)) {
        case 'D' -> Outcome.failed(reference, "The customer declined the request.");
        case 'F' -> Outcome.failed(reference, "Insufficient funds.");
        case 'T' -> Outcome.pending(reference);
        default -> Outcome.confirmed(reference);
      };
    }

    static String outcomeFor(String phone) {
      String digits = phone == null ? "" : phone.replaceAll("\\D", "");
      String last = digits.length() < 2 ? "" : digits.substring(digits.length() - 2);
      return switch (last) {
        case "01" -> "D";
        case "02" -> "F";
        case "03" -> "T";
        default -> "A";
      };
    }
  }

  boolean pushAvailable(String adapterCode) {
    PaymentAdapter adapter = adapters.get(adapterCode);
    return adapter != null && adapter.available();
  }
}
