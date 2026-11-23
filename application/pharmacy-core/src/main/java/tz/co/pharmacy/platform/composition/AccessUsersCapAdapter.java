package tz.co.pharmacy.platform.composition;

import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.outbound.UsersCap;
import tz.co.pharmacy.modules.tenancy.ports.inbound.Entitlements;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;

/**
 * Binds access's users cap to the pharmacy's subscription plan (tenancy). The entitlements are
 * looked up when used: tenancy counts users through access, so the two would otherwise need each
 * other to be built.
 */
@Component
class AccessUsersCapAdapter implements UsersCap {

  private final ObjectProvider<Entitlements> entitlements;

  AccessUsersCapAdapter(ObjectProvider<Entitlements> entitlements) {
    this.entitlements = entitlements;
  }

  @Override
  public void requireRoom(UUID tenantUid, long inUse, long adding) {
    entitlements.getObject().requireRoomGiven(tenantUid, PlanLimit.USERS, inUse, adding);
  }
}
