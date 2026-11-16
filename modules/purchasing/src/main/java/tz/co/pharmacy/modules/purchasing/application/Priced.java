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
      checkPlausible(currencies, currency, requestedRate);
      return new Priced(currency, requestedRate);
    }
    if (followedRate != null) {
      return new Priced(currency, followedRate);
    }
    return new Priced(currency, currencies.rateToBase(currency, LocalDate.now()));
  }

  /** How far a typed rate may stray from the recorded one before it is taken for a mistake. */
  static final BigDecimal TOLERANCE = new BigDecimal("0.25");

  /**
   * A typed rate is refused when today's recorded rate exists and differs by more than 25%: a rate
   * of 1 for TZS in a USD pharmacy once valued a whole order as if a shilling were a dollar. With
   * no recorded rate the typed one stands (it is how a first rate is entered).
   */
  static void checkPlausible(PurchasingCurrencies currencies, String currency, BigDecimal rate) {
    BigDecimal recorded;
    try {
      recorded = currencies.rateToBase(currency, LocalDate.now());
    } catch (Refusal noRate) {
      return;
    }
    if (recorded == null || recorded.signum() <= 0) {
      return;
    }
    BigDecimal deviation =
        rate.subtract(recorded).abs().divide(recorded, 6, java.math.RoundingMode.HALF_EVEN);
    if (deviation.compareTo(TOLERANCE) > 0) {
      throw Refusal.invalid(
          "rate_out_of_range",
          "The rate "
              + rate.stripTrailingZeros().toPlainString()
              + " is more than 25% away from today's recorded "
              + currency
              + " rate of "
              + recorded.stripTrailingZeros().toPlainString()
              + ". Check it, or record the new rate under Finance → Exchange rates first.",
          "exchangeRate");
    }
  }
}
