package tz.co.pharmacy.platform.composition;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.finance.ports.outbound.VatRegistration;
import tz.co.pharmacy.modules.tenancy.ports.inbound.SettingsService;

/** Binds finance's read of tenancy: whether the pharmacy is VAT-registered (STD-2). */
@Component
@RequiredArgsConstructor
class VatRegistrationAdapter implements VatRegistration {

  private final SettingsService settings;

  @Override
  public boolean vatRegistered() {
    return settings.current().vatRegistered();
  }
}
