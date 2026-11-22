package tz.co.pharmacy.application.onboarding.internal;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.application.onboarding.ports.inbound.OnboardTenantCommand;
import tz.co.pharmacy.application.onboarding.ports.inbound.TenantOnboarding;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationService;
import tz.co.pharmacy.modules.access.ports.inbound.InviteCommand;
import tz.co.pharmacy.modules.access.ports.inbound.SeededTenant;
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
  private final InvitationService invitations;

  TenantOnboardingService(
      TenantService tenantService,
      UserAdministration userAdministration,
      TenantSwitch tenantSwitch,
      InvitationService invitations) {
    this.tenantService = tenantService;
    this.userAdministration = userAdministration;
    this.tenantSwitch = tenantSwitch;
    this.invitations = invitations;
  }

  @Override
  @Transactional
  public Onboarded onboard(OnboardTenantCommand command) {
    Checks.required(command, "tenant");
    Checks.required(command.administrator(), "admin");
    TenantView tenant = tenantService.register(command.tenant());
    Boolean invited =
        tenantSwitch.within(
            tenant.uid(),
            () -> {
              SeededTenant seeded = userAdministration.seedTenant(command.administrator());
              if (seeded.admin() != null) {
                return false;
              }
              // The email already has an account: its owner joins as ADMIN by accepting.
              invitations.invite(
                  new InviteCommand(
                      command.administrator().name(),
                      command.administrator().email(),
                      seeded.adminRoleUid(),
                      true,
                      List.of()));
              return true;
            });
    return new Onboarded(tenant, Boolean.TRUE.equals(invited));
  }
}
