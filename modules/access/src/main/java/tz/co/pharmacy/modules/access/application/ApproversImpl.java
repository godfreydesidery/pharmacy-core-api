package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.access.domain.Membership;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.Approvers;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * The manager approving on someone else's screen proves who they are with their own password, as at
 * sign-in: a wrong one counts toward their account's lock (so the approval box cannot be used to
 * guess it), and a locked account cannot approve.
 */
@Service
class ApproversImpl implements Approvers {

  private final UserRepository users;
  private final MembershipRepository memberships;
  private final Passwords passwords;
  private final SignInGuard guard;
  private final AuthenticationServiceImpl authentication;
  private final Clock clock;

  ApproversImpl(
      UserRepository users,
      MembershipRepository memberships,
      Passwords passwords,
      SignInGuard guard,
      AuthenticationServiceImpl authentication,
      Clock clock) {
    this.users = users;
    this.memberships = memberships;
    this.passwords = passwords;
    this.guard = guard;
    this.authentication = authentication;
    this.clock = clock;
  }

  @Override
  @Transactional(readOnly = true)
  public Approver verify(String login, String password, ClientInfo client) {
    ClientInfo from = client == null ? ClientInfo.UNKNOWN : client;
    UUID tenant = TenantContext.requireTenant();
    String normalised = login == null ? "" : login.trim().toLowerCase();
    Instant now = clock.instant();
    User user = users.findByEmail(normalised).filter(User::isActive).orElse(null);
    guard.checkAccount(user, normalised, now);
    if (user == null || !passwords.matches(password, user.getPasswordHash())) {
      boolean locked = authentication.recordFailure(user, normalised, from, now, "approval");
      if (locked) {
        throw guard.locked(guard.lockFor());
      }
      throw Refusal.invalid(
          "invalid_credentials", "The approver's sign-in or password is wrong.", "password");
    }
    UUID requester = ExecutionContext.actor().uid();
    if (user.getUid().equals(requester)) {
      // The owner alone, with nobody else who could approve here, approves their own request
      // after typing their password; it is recorded as a self-approval.
      if (selfApprovalAllowed()) {
        return new Approver(user.getUid(), user.getName(), true);
      }
      throw Refusal.invalid(
          "approver_is_requester",
          "Someone else must approve: the person asking cannot approve their own action.",
          "login");
    }
    Optional<Membership> membership =
        memberships.findByUserUidAndTenantUid(user.getUid(), tenant).filter(Membership::isActive);
    UUID branch = ExecutionContext.branchUid().orElse(null);
    boolean may =
        membership.isPresent()
            && membership.get().getRole().getPermissions().contains(Permissions.APPROVALS_GRANT)
            && (branch == null
                || membership.get().isAllBranches()
                || membership.get().getBranchUids().contains(branch));
    if (!may) {
      throw Refusal.forbidden(
          "not_an_approver", user.getName() + " may not approve here (no Approve permission).");
    }
    return new Approver(user.getUid(), user.getName());
  }

  @Override
  @Transactional(readOnly = true)
  public boolean selfApprovalAllowed() {
    UUID tenant = TenantContext.tenantUid().orElse(null);
    if (tenant == null || ExecutionContext.current().isEmpty() || ExecutionContext.rootAdmin()) {
      return false;
    }
    UUID requester = ExecutionContext.actor().uid();
    UUID branch = ExecutionContext.branchUid().orElse(null);
    boolean owner =
        memberships
            .findByUserUidAndTenantUid(requester, tenant)
            .filter(Membership::isActive)
            .filter(ApproversImpl::isOwner)
            .isPresent();
    if (!owner) {
      return false;
    }
    List<UUID> others =
        memberships.findAllByTenantUidAndActiveTrue(tenant).stream()
            .filter(m -> !m.getUserUid().equals(requester))
            .filter(m -> mayApprove(m, branch))
            .map(Membership::getUserUid)
            .toList();
    return others.isEmpty() || users.findAllByUidIn(others).stream().noneMatch(User::isActive);
  }

  /** The pharmacy's owner: a member with the built-in Administrator role. */
  private static boolean isOwner(Membership membership) {
    return membership.getRole().isSystem() && "ADMIN".equals(membership.getRole().getCode());
  }

  private static boolean mayApprove(Membership membership, UUID branch) {
    return membership.getRole().getPermissions().contains(Permissions.APPROVALS_GRANT)
        && (branch == null
            || membership.isAllBranches()
            || membership.getBranchUids().contains(branch));
  }
}
