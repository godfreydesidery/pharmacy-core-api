package tz.co.pharmacy.modules.fx.ports.outbound;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

/**
 * An exchange-rate source, such as a central bank feed or a commercial API. The default adapter
 * returns nothing; a real one is chosen with {@code pharmacy.integrations.fx.provider}.
 */
public interface RateProvider {

  String name();

  /** Units of each of {@code quotes} per 1 {@code base}, for those it knows. */
  Map<String, BigDecimal> latest(String base, Set<String> quotes);
}
