package tz.co.pharmacy.platform.composition;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.catalogue.ports.inbound.CatalogueCounts;
import tz.co.pharmacy.modules.notifications.ports.inbound.NotificationUsage;
import tz.co.pharmacy.modules.tenancy.ports.inbound.BranchService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantService;
import tz.co.pharmacy.modules.tenancy.ports.inbound.TenantView;
import tz.co.pharmacy.modules.tenancy.ports.outbound.TenantUsage;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Binds the subscription limits to what the tenant has: branches (tenancy), users (access: active
 * members and pending invitations), products (catalogue) and SMS sent this month on the platform's
 * account (notifications; the month in the pharmacy's time zone, the count the SMS cap enforces).
 * Each count runs as that tenant, in a transaction of its own, so the root administrator can see
 * another pharmacy's usage.
 */
@Component
class TenantUsageAdapter implements TenantUsage {

  private final BranchService branches;
  private final UserAdministration users;
  private final CatalogueCounts catalogue;
  private final NotificationUsage notifications;
  private final ObjectProvider<TenantService> tenants;

  TenantUsageAdapter(
      BranchService branches,
      UserAdministration users,
      CatalogueCounts catalogue,
      NotificationUsage notifications,
      ObjectProvider<TenantService> tenants) {
    this.branches = branches;
    this.users = users;
    this.catalogue = catalogue;
    this.notifications = notifications;
    this.tenants = tenants;
  }

  @Override
  public long used(UUID tenantUid, PlanLimit limit) {
    return switch (limit) {
      case SMS -> notifications.platformSmsThisMonth(tenantUid, zone(tenantUid));
      case BRANCHES -> TenantContext.callAs(tenantUid, () -> (long) branches.list().size());
      case PRODUCTS -> TenantContext.callAs(tenantUid, catalogue::productCount);
      // Active members and pending invitations: an invitation holds a seat (INT-6).
      case USERS -> TenantContext.callAs(tenantUid, users::seatsInUse);
    };
  }

  private ZoneId zone(UUID tenantUid) {
    String timezone = tenants.getObject().find(tenantUid).map(TenantView::timezone).orElse(null);
    try {
      return timezone == null || timezone.isBlank() ? ZoneOffset.UTC : ZoneId.of(timezone);
    } catch (DateTimeException unknown) {
      return ZoneOffset.UTC;
    }
  }
}
