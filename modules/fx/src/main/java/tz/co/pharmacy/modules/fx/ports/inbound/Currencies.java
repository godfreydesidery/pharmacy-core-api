package tz.co.pharmacy.modules.fx.ports.inbound;

import java.util.List;
import java.util.Optional;

/** The currencies the platform offers, with their ISO 4217 minor units. */
public interface Currencies {

  List<CurrencyView> all();

  Optional<CurrencyView> find(String code);
}
