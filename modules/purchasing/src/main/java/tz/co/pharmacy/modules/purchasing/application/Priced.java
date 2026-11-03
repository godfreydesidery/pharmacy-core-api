package tz.co.pharmacy.modules.purchasing.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingCurrencies;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The currency a purchasing document is priced in and its locked rate (base units per unit).
 *
 * <ul>
 *   <li>The base currency always has rate 1.
 *   <li>A document that follows another (a receipt of an order) takes that one's currency; naming
 *       another currency is refused, and its rate is the order's unless overridden.
 *   <li>Otherwise a foreign currency must be accepted, and takes the given rate or today's.
 * </ul>
 */
record Priced(String currency, BigDecimal rate) {

  static Priced of(
      PurchasingCurrencies currencies,
      String requestedCurrency,
      BigDecimal requestedRate,
      String followedCurrency,
      BigDecimal followedRate) {
    String base = currencies.baseCurrency();
    String currency;
    if (followedCurrency != null) {
      currency = followedCurrency;
      if (requestedCurrency != null
          && !requestedCurrency.isBlank()
          && !requestedCurrency.trim().equalsIgnoreCase(followedCurrency)) {
        throw Refusal.invalid(
            "currency_mismatch",
            "The receipt must be in the order's currency, " + followedCurrency + ".",
            "currency");
      }
    } else {
      currency =
          requestedCurrency == null || requestedCurrency.isBlank()
              ? base
              : currencies.accepted(requestedCurrency);
    }
    if (currency.equals(base)) {
      return new Priced(base, BigDecimal.ONE);
    }
    if (requestedRate != null) {
      if (requestedRate.signum() <= 0) {
        throw Refusal.invalid(
            "invalid_exchange_rate", "exchangeRate must be more than zero.", "exchangeRate");
      }
      return new Priced(currency, requestedRate);
    }
    if (followedRate != null) {
      return new Priced(currency, followedRate);
    }
    return new Priced(currency, currencies.rateToBase(currency, LocalDate.now()));
  }
}
