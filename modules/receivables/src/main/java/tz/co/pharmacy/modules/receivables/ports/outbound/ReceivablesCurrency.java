package tz.co.pharmacy.modules.receivables.ports.outbound;

import java.math.BigDecimal;
import java.time.LocalDate;

/** The tenant's base currency and the day's rate of another currency into it, from fx. */
public interface ReceivablesCurrency {

  String baseCurrency();

  /** Base units per 1 unit of {@code currency} on {@code on}; 422 rate_missing when none. */
  BigDecimal rateToBase(String currency, LocalDate on);
}
