package tz.co.pharmacy.platform.composition;

import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.catalogue.ports.outbound.PricingSettings;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;

/** Binds the catalogue's read of tenancy: the deepest discount a till may give. */
@Component
@RequiredArgsConstructor
class CatalogueSettingsAdapter implements PricingSettings {

  private final SettingsService settings;

  @Override
  public BigDecimal maxDiscountPercent() {
    return settings.current().maxDiscountPercent();
  }
}
