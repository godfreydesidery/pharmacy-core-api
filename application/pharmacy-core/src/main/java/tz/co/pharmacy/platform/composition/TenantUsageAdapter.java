package tz.co.pharmacy.platform.composition;

import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.inbound.MemberView;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueCounts;
import tz.co.pharmacy.modules.notifications.ports.inbound.Channel;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationUsage;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;
import tz.co.pharmacy.modules.tenancy.ports.outbound.TenantUsage;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Binds the subscription limits to what the tenant has: branches (tenancy), active users (access),
 * products (catalogue) and SMS sent this month (notifications). Each count runs as that tenant, in
 * a transaction of its own, so the root administrator can see another pharmacy's usage.
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
      case USERS -> TenantContext.callAs(tenantUid, this::activeMembers);
    };
  }

  private long activeMembers() {
    long count = 0;
    String cursor = null;
    for (int page = 0; page < 100; page++) {
      Page<MemberView> members = users.members(null, PageQuery.of(cursor, 100));
      count += members.items().stream().filter(MemberView::active).count();
      cursor = members.nextCursor();
      if (cursor == null) {
        break;
      }
    }
    return count;
  }
}
