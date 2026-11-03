package tz.co.pharmacy.modules.fx.application;

import java.util.Currency;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;
import tz.co.pharmacy.modules.fx.ports.inbound.Currencies;
import tz.co.pharmacy.modules.fx.ports.inbound.CurrencyView;

/**
 * The offered currencies: the East African ones first, where the platform starts, then the main
 * trading currencies. Names and minor units come from the JDK's ISO 4217 table; any other valid ISO
 * code is also found, so a tenant is never stuck with a currency missing from the list.
 */
@Service
class CurrenciesImpl implements Currencies {

  static final List<String> OFFERED =
      List.of(
          "TZS", "KES", "UGX", "RWF", "BIF", "CDF", "ETB", "MWK", "ZMW", "MZN", "USD", "EUR", "GBP",
          "ZAR", "CNY", "INR", "AED", "JPY", "CHF", "CAD");

  private static final List<CurrencyView> ALL = OFFERED.stream().map(CurrenciesImpl::view).toList();

  @Override
  public List<CurrencyView> all() {
    return ALL;
  }

  @Override
  public Optional<CurrencyView> find(String code) {
    if (code == null || code.isBlank()) {
      return Optional.empty();
    }
    String upper = code.trim().toUpperCase(Locale.ROOT);
    return ALL.stream()
        .filter(currency -> currency.code().equals(upper))
        .findFirst()
        .or(
            () -> {
              try {
                return Optional.of(view(Currency.getInstance(upper).getCurrencyCode()));
              } catch (IllegalArgumentException unknown) {
                return Optional.empty();
              }
            });
  }

  static CurrencyView view(String code) {
    Currency currency = Currency.getInstance(code);
    return new CurrencyView(
        code,
        currency.getDisplayName(Locale.ENGLISH),
        Conversion.minorUnits(code),
        currency.getSymbol(Locale.ENGLISH));
  }
}
