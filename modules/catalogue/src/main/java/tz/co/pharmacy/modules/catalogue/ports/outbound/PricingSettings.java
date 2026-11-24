package tz.co.pharmacy.modules.catalogue.ports.outbound;

import java.math.BigDecimal;

/** The pharmacy's pricing limits and its standard VAT rate (tenancy settings). */
public interface PricingSettings {

  /** The deepest discount a till may give, in percent; null for no limit. */
  BigDecimal maxDiscountPercent();

  /** The standard VAT rate in percent, which STANDARD products are sold at (STD-2). */
  default BigDecimal standardVatRatePercent() {
    return BigDecimal.ZERO;
  }
}
