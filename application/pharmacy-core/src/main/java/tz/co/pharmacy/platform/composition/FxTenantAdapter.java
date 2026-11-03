package tz.co.pharmacy.platform.composition;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.fx.ports.outbound.FxTenant;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsView;

/**
 * Binds fx's view of the tenant to tenancy: the base currency and the accepted currencies, the base
 * always among them. It needs nothing of fx, so fx and the adapters that need fx's rates do not
 * depend on each other.
 */
@Component
class FxTenantAdapter implements FxTenant {

  private final SettingsService settings;

  FxTenantAdapter(SettingsService settings) {
    this.settings = settings;
  }

  @Override
  public String baseCurrency() {
    return settings.current().currency();
  }

  @Override
  public Set<String> acceptedCurrencies() {
    SettingsView current = settings.current();
    Set<String> accepted = new LinkedHashSet<>();
    accepted.add(current.currency());
    if (current.acceptedCurrencies() != null) {
      accepted.addAll(current.acceptedCurrencies());
    }
    return accepted;
  }
}
