package tz.co.pharmacy.modules.tenancy.ports.outbound;

import java.util.UUID;
import tz.co.pharmacy.modules.tenancy.ports.inbound.PlanLimit;

/**
 * How much of a plan's capped quantities a tenant uses (access, catalogue, notifications). Called
 * outside a transaction; the binding runs each count as that tenant.
 */
public interface TenantUsage {

  /** Branches, active users, products, or SMS sent this calendar month. */
  long used(UUID tenantUid, PlanLimit limit);
}
