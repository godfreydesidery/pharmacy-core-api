package tz.co.pharmacy.modules.catalogue.ports.outbound;

import java.math.BigDecimal;

/** The pharmacy's pricing limits (tenancy settings). */
public interface PricingSettings {

  /** The deepest discount a till may give, in percent; null for no limit. */
  BigDecimal maxDiscountPercent();
}
