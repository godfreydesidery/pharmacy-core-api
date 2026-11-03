package tz.co.pharmacy.application.onboarding.internal;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.application.onboarding.ports.inbound.OnboardTenantCommand;
import tz.co.pharmacy.application.onboarding.ports.inbound.TenantOnboarding;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.persistence.TenantSwitch;

@Service
class TenantOnboardingService implements TenantOnboarding {

  private final TenantService tenantService;
  private final UserAdministration userAdministration;
  private final TenantSwitch tenantSwitch;

  TenantOnboardingService(
      TenantService tenantService,
      UserAdministration userAdministration,
      TenantSwitch tenantSwitch) {
    this.tenantService = tenantService;
    this.userAdministration = userAdministration;
    this.tenantSwitch = tenantSwitch;
  }

  @Override
  @Transactional
  public TenantView onboard(OnboardTenantCommand command) {
    Checks.required(command, "tenant");
    Checks.required(command.administrator(), "admin");
    TenantView tenant = tenantService.register(command.tenant());
    tenantSwitch.within(tenant.uid(), () -> userAdministration.seedTenant(command.administrator()));
    return tenant;
  }
}
