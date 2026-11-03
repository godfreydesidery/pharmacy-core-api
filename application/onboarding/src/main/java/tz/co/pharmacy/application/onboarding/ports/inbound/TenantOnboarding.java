package tz.co.pharmacy.application.onboarding.ports.inbound;

import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;

public interface TenantOnboarding {

  /**
   * Registers the tenant with its head office and default settings, seeds its default roles and
   * makes the administrator its ADMIN; nothing is written unless all of it is.
   */
  TenantView onboard(OnboardTenantCommand command);
}
