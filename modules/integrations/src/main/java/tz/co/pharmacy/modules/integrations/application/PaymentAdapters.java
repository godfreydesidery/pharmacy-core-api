package tz.co.pharmacy.modules.integrations.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The adapters that push a payment request to a customer's phone and report its outcome, by the
 * registry's adapter code. SIMULATOR confirms every request, for training and tests. The provider
 * adapters (M-Pesa, Mixx by Yas, Airtel Money, Selcom, AzamPay) are the plug points for each
 * provider's API: until one is connected with merchant credentials and tested against the
 * provider's sandbox it reports itself unavailable, and the cashier records the provider's
 * confirmation code by hand, which the till accepts as the reference.
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
  }

  /** One provider's push flow. */
  interface PaymentAdapter {

    /** Whether requests can be pushed through this adapter now. */
    boolean available();

    Outcome push(String phone, BigDecimal amount, String currency, String accountReference);

    Outcome status(String providerReference);
  }

  private final Map<String, PaymentAdapter> adapters;

  PaymentAdapters() {
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
    PaymentAdapter simulator =
        new PaymentAdapter() {
          @Override
          public boolean available() {
            return true;
          }

          @Override
          public Outcome push(
              String phone, BigDecimal amount, String currency, String accountReference) {
            return Outcome.pending(
                "SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
          }

          @Override
          public Outcome status(String providerReference) {
            return Outcome.confirmed(providerReference);
          }
        };
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

  boolean pushAvailable(String adapterCode) {
    PaymentAdapter adapter = adapters.get(adapterCode);
    return adapter != null && adapter.available();
  }
}
