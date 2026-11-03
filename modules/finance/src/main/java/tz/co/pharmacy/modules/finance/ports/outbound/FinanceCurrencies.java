package tz.co.pharmacy.modules.finance.ports.outbound;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The tenant's currencies and rates, for payments in a foreign currency. */
public interface FinanceCurrencies {

  String baseCurrency();

  /** The ISO code in upper case; 422 currency_not_accepted (field currency) unless accepted. */
  String accepted(String currency);

  /** Base units per 1 unit of {@code currency} on the day; 422 rate_missing. */
  BigDecimal rateToBase(String currency, LocalDate on);

  /** {@code amount} times {@code rate}, rounded half-even to the base currency's minor units. */
  BigDecimal toBase(BigDecimal amount, BigDecimal rate);
}
