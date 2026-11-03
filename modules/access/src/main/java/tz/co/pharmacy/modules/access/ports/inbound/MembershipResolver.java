package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.Optional;
import java.util.UUID;

public interface MembershipResolver {

  /** The user's membership in the tenant (call with TenantContext set to both), or empty. */
  Optional<MembershipGrant> resolve(UUID userUid, UUID tenantUid);
}
