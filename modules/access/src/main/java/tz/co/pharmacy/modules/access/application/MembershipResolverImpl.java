package tz.co.pharmacy.modules.access.application;

import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.ports.inbound.MembershipGrant;
import tz.co.pharmacy.modules.access.ports.inbound.MembershipResolver;

@Service
class MembershipResolverImpl implements MembershipResolver {

  private final MembershipRepository memberships;

  MembershipResolverImpl(MembershipRepository memberships) {
    this.memberships = memberships;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<MembershipGrant> resolve(UUID userUid, UUID tenantUid) {
    if (userUid == null || tenantUid == null) {
      return Optional.empty();
    }
    return memberships.findByUserUidAndTenantUid(userUid, tenantUid).map(AccessViews::grant);
  }
}
