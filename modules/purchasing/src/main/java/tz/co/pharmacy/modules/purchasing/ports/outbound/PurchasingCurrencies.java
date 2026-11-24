package tz.co.pharmacy.modules.purchasing.ports.outbound;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The tenant's currencies and rates, as purchasing needs them for foreign-currency documents. */
public interface PurchasingCurrencies {

  String baseCurrency();

  /** The ISO code in upper case; 422 currency_not_accepted (field currency) unless accepted. */
  String accepted(String currency);

  /** Base units per 1 unit of {@code currency} on the day; 422 rate_missing. */
  BigDecimal rateToBase(String currency, LocalDate on);

  /** {@code amount} times {@code rate}, rounded half-up to the base currency's minor units. */
  BigDecimal toBase(BigDecimal amount, BigDecimal rate);

  /** Today in the pharmacy's time zone; the rate of the day is looked up for it. */
  default java.time.LocalDate today() {
    return java.time.LocalDate.now(java.time.ZoneOffset.UTC);
  }
}
