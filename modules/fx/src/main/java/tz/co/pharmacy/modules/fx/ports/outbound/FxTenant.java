package tz.co.pharmacy.modules.fx.ports.outbound;

import java.util.Set;

/** The current tenant's base currency and the currencies it accepts (the base among them). */
public interface FxTenant {

  String baseCurrency();

  Set<String> acceptedCurrencies();
}
