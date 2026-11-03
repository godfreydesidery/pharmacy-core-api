package tz.co.pharmacy.application.onboarding.ports.inbound;

import tz.co.pharmacy.modules.access.ports.inbound.NewUserCommand;
import tz.co.pharmacy.modules.tenancy.ports.inbound.RegisterTenantCommand;

public record OnboardTenantCommand(RegisterTenantCommand tenant, NewUserCommand administrator) {}
