package tz.co.pharmacy.modules.sales.ports.outbound;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The tenant's currencies and rates, as sales needs them for foreign-currency tenders. Prices and
 * totals are always in the base currency; only payments may be in another accepted currency.
 */
public interface SalesCurrencies {

  String baseCurrency();

  /** The ISO code in upper case; 422 currency_not_accepted (field currency) unless accepted. */
  String accepted(String currency);

  /** Base units per 1 unit of {@code currency} on the day; 422 rate_missing. */
  BigDecimal rateToBase(String currency, LocalDate on);

  /** {@code amount} rounded half-even to {@code currency}'s ISO 4217 minor units. */
  BigDecimal round(BigDecimal amount, String currency);
}
