package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.access.domain.Membership;
import tz.co.pharmacy.modules.access.domain.Role;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.RoleRepository;
import tz.co.pharmacy.modules.access.persistence.SessionRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.CreateManagedLoginCommand;
import tz.co.pharmacy.modules.access.ports.inbound.ManagedLogins;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

@Service
class ManagedLoginsImpl implements ManagedLogins {

  /** 2 to 32: starts with a letter or digit, then letters, digits, dot, dash or underscore. */
  private static final Pattern USERNAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{1,31}$");

  private final UserRepository users;
  private final MembershipRepository memberships;
  private final RoleRepository roles;
  private final SessionRepository sessions;
  private final Passwords passwords;
  private final AuditLog audit;
  private final Clock clock;

  ManagedLoginsImpl(
      UserRepository users,
      MembershipRepository memberships,
      RoleRepository roles,
      SessionRepository sessions,
      Passwords passwords,
      AuditLog audit,
      Clock clock) {
    this.users = users;
    this.memberships = memberships;
    this.roles = roles;
    this.sessions = sessions;
    this.passwords = passwords;
    this.audit = audit;
    this.clock = clock;
  }

  @Override
  @Transactional
  public Created create(CreateManagedLoginCommand command) {
    Checks.required(command, "login");
    UUID tenant = TenantContext.requireTenant();
    String code = Checks.code(command.tenantCode(), "tenantCode");
    String username = username(command.username());
    String signIn = username + "@" + code.toLowerCase(Locale.ROOT);
    String name = Checks.text(command.name(), "name", 120);
    Role role = role(command.roleUid());
    Set<UUID> branches = branches(command.allBranches(), command.branchUids());
    String phone = UserAdministrationImpl.phone(command.phone());
    String contactEmail = contactEmail(command.contactEmail());
    if (users.findByEmail(signIn).isPresent()) {
      throw Refusal.conflict(
          "username_taken", "The username " + username + " is already used in this pharmacy.");
    }
    String temporary = TemporaryPasswords.next();
    User user = User.managed(tenant, signIn, name, passwords.hash(temporary, "password"));
    user.changeManagedContact(name, phone, contactEmail);
    users.save(user);
    Membership membership =
        memberships.save(new Membership(user.getUid(), role, command.allBranches(), branches));
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("signIn", signIn);
    details.put("role", role.getCode());
    audit.record("MANAGED_LOGIN_CREATED", "User", user.getUid(), null, details);
    return new Created(AccessViews.member(membership, user), signIn, temporary);
  }

  @Override
  @Transactional
  public Reset resetPassword(UUID membershipUid) {
    UUID tenant = TenantContext.requireTenant();
    Membership membership =
        memberships
            .findByUidAndTenantUid(Checks.required(membershipUid, "membershipUid"), tenant)
            .orElseThrow(() -> Refusal.notFound("membership_not_found", "No such member."));
    User user =
        users
            .findByUid(membership.getUserUid())
            .orElseThrow(() -> Refusal.notFound("membership_not_found", "No such member."));
    if (!user.isManagedBy(tenant)) {
      throw Refusal.invalid(
          "not_managed",
          "This person signs in with their own account; only they can change its password.",
          "membershipUid");
    }
    String temporary = TemporaryPasswords.next();
    user.resetByAdmin(passwords.hash(temporary, "password"));
    int ended = sessions.revokeAll(user.getUid(), clock.instant(), null);
    audit.record(
        "PASSWORD_RESET_BY_ADMIN",
        "User",
        user.getUid(),
        null,
        Map.of("signIn", user.getEmail(), "sessionsEnded", ended));
    return new Reset(AccessViews.member(membership, user), user.getEmail(), temporary);
  }

  /** Lower case; 422 invalid_username. */
  static String username(String value) {
    String username = Checks.text(value, "username", 32).toLowerCase(Locale.ROOT);
    if (!USERNAME.matcher(username).matches()) {
      throw Refusal.invalid(
          "invalid_username",
          "A username is 2 to 32 letters or digits, and may use . - or _ (such as juma or"
              + " mary.k).",
          "username");
    }
    return username;
  }

  static String contactEmail(String value) {
    return value == null || value.isBlank() ? null : Checks.email(value, "contactEmail");
  }

  private Role role(UUID roleUid) {
    Checks.required(roleUid, "roleUid");
    return roles
        .findByUid(roleUid)
        .filter(role -> role.getTenantUid().equals(TenantContext.requireTenant()))
        .orElseThrow(() -> Refusal.invalid("role_not_found", "No such role.", "roleUid"));
  }

  private static Set<UUID> branches(boolean allBranches, java.util.Collection<UUID> branchUids) {
    if (allBranches) {
      return Set.of();
    }
    if (branchUids == null || branchUids.isEmpty()) {
      throw Refusal.invalid(
          "invalid_branch_uids", "Name at least one branch, or allow every branch.", "branchUids");
    }
    return new LinkedHashSet<>(branchUids);
  }
}
