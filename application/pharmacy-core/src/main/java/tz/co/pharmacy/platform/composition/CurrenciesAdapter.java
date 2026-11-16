package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.finance.ports.outbound.FinanceCurrencies;
import tz.co.pharmacy.modules.fx.ports.inbound.ExchangeRates;
import tz.co.pharmacy.modules.purchasing.ports.outbound.PurchasingCurrencies;
import tz.co.pharmacy.modules.sales.ports.outbound.SalesCurrencies;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.kernel.TenantCalendar;

/**
 * Binds the currency needs of purchasing, sales and finance to tenancy (the base and accepted
 * currencies, through {@link FxTenantAdapter}) and fx (rates and minor units). A foreign currency
 * must be one the tenant accepts; conversion to base rounds half-even to the base currency's ISO
 * 4217 minor units.
 */
@Component
class CurrenciesAdapter implements PurchasingCurrencies, SalesCurrencies, FinanceCurrencies {

  private final FxTenantAdapter tenant;
  private final ExchangeRates rates;
  private final TenantCalendar calendar;

  CurrenciesAdapter(FxTenantAdapter tenant, ExchangeRates rates, TenantCalendar calendar) {
    this.calendar = calendar;
    this.tenant = tenant;
    this.rates = rates;
  }

  @Override
  public String baseCurrency() {
    return tenant.baseCurrency();
  }

  private Set<String> acceptedCurrencies() {
    return tenant.acceptedCurrencies();
  }

  @Override
  public String accepted(String currency) {
    String code = currency.trim().toUpperCase(Locale.ROOT);
    if (!acceptedCurrencies().contains(code)) {
      throw Refusal.invalid(
          "currency_not_accepted",
          code + " is not one of the currencies this pharmacy accepts (Settings → Currencies).",
          "currency");
    }
    return code;
  }

  @Override
  public BigDecimal rateToBase(String currency, LocalDate on) {
    return rates.rate(currency, baseCurrency(), on);
  }

  @Override
  public BigDecimal toBase(BigDecimal amount, BigDecimal rate) {
    return amount.multiply(rate).setScale(rates.minorUnits(baseCurrency()), RoundingMode.HALF_EVEN);
  }

  @Override
  public BigDecimal round(BigDecimal amount, String currency) {
    return amount.setScale(rates.minorUnits(currency), RoundingMode.HALF_EVEN);
  }

  @Override
  public java.time.LocalDate today() {
    return calendar.today();
  }
}
