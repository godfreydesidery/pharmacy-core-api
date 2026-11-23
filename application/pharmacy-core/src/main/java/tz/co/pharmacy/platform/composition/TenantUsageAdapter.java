package tz.co.pharmacy.platform.composition;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueCounts;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationUsage;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;
import tz.co.pharmacy.modules.tenancy.ports.outbound.TenantUsage;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Binds the subscription limits to what the tenant has: branches (tenancy), users (access: active
 * members and pending invitations), products (catalogue) and SMS sent this month (notifications).
 * Each count runs as that tenant, in a transaction of its own, so the root administrator can see
 * another pharmacy's usage.
 */
@Component
class TenantUsageAdapter implements TenantUsage {

  private final BranchService branches;
  private final UserAdministration users;
  private final CatalogueCounts catalogue;
  private final NotificationUsage notifications;

  TenantUsageAdapter(
      BranchService branches,
      UserAdministration users,
      CatalogueCounts catalogue,
      NotificationUsage notifications) {
    this.branches = branches;
    this.users = users;
    this.catalogue = catalogue;
    this.notifications = notifications;
  }

  @Override
  public long used(UUID tenantUid, PlanLimit limit) {
    return switch (limit) {
      case SMS -> notifications.sentThisMonth(tenantUid, Channel.SMS);
      case BRANCHES -> TenantContext.callAs(tenantUid, () -> (long) branches.list().size());
      case PRODUCTS -> TenantContext.callAs(tenantUid, catalogue::productCount);
      // Active members and pending invitations: an invitation holds a seat (INT-6).
      case USERS -> TenantContext.callAs(tenantUid, users::seatsInUse);
    };
  }
}
