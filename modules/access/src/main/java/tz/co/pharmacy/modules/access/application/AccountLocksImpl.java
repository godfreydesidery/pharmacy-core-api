package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.access.domain.Membership;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.AccountLocks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Specs;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

@Service
class AccountLocksImpl implements AccountLocks {

  private final MembershipRepository memberships;
  private final UserRepository users;
  private final AuditLog audit;
  private final Clock clock;

  AccountLocksImpl(
      MembershipRepository memberships, UserRepository users, AuditLog audit, Clock clock) {
    this.memberships = memberships;
    this.users = users;
    this.audit = audit;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public List<LockedMember> locked() {
    UUID tenant = TenantContext.requireTenant();
    // Only the people who work here now: a former employer learns nothing of the account a person
    // keeps (adversarial review ISO-6).
    Map<UUID, Membership> byUser =
        memberships
            .findAll(
                Specs.<Membership>equal("tenantUid", tenant)
                    .and(Specs.<Membership>equal("active", true)))
            .stream()
            .collect(Collectors.toMap(Membership::getUserUid, Function.identity(), (a, b) -> a));
    Instant now = clock.instant();
    return users.findAllByUidIn(byUser.keySet()).stream()
        .filter(user -> user.lockedAt(now))
        .map(
            user ->
                new LockedMember(
                    byUser.get(user.getUid()).getUid(), user.getUid(), user.getLockedUntil()))
        .toList();
  }

  @Override
  @Transactional
  public void unlock(UUID membershipUid) {
    Membership membership =
        memberships
            .findByUidAndTenantUid(membershipUid, TenantContext.requireTenant())
            .orElseThrow(() -> Refusal.notFound("membership_not_found", "No such member."));
    User user =
        users
            .findByUid(membership.getUserUid())
            .orElseThrow(() -> Refusal.notFound("membership_not_found", "No such member."));
    if (!user.isManagedBy(membership.getTenantUid()) && !membership.isActive()) {
      // Someone who left: their own account is no business of this pharmacy's, locked or not.
      throw Refusal.invalid(
          "not_managed",
          "This person signs in with their own account and no longer works here.",
          "membershipUid");
    }
    if (!user.isManagedBy(membership.getTenantUid())) {
      // A person's own account is theirs: a lock that may have come from guessing at another
      // pharmacy ends by itself, or when they reset their password; no pharmacy lifts it.
      throw Refusal.invalid(
          "not_managed",
          user.getLockedUntil() == null
              ? "This person's own account is not locked."
              : "This person's own account unlocks by itself at "
                  + user.getLockedUntil()
                  + ", or as soon as they reset their password.",
          "membershipUid");
    }
    boolean wasLocked = user.lockedAt(clock.instant());
    user.unlock();
    audit.record("ACCOUNT_UNLOCKED", "User", user.getUid(), null, Map.of("wasLocked", wasLocked));
  }
}
