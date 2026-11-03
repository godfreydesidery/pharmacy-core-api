package tz.co.pharmacy.modules.access.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.access.domain.Membership;
import tz.co.pharmacy.modules.access.domain.Role;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.RoleRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.AddMemberCommand;
import tz.co.pharmacy.modules.access.ports.inbound.ChangeMemberCommand;
import tz.co.pharmacy.modules.access.ports.inbound.MemberView;
import tz.co.pharmacy.modules.access.ports.inbound.NewUserCommand;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.RoleCommand;
import tz.co.pharmacy.modules.access.ports.inbound.RoleView;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.access.ports.inbound.UserView;
import tz.co.pharmacy.platform.kernel.Checks;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.Keyset;
import tz.co.pharmacy.platform.persistence.Specs;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

@Service
class UserAdministrationImpl implements UserAdministration {

  private static final String ROLE_CODE = "^[A-Z][A-Z0-9_]{1,31}$";

  private final UserRepository users;
  private final RoleRepository roles;
  private final MembershipRepository memberships;
  private final Passwords passwords;
  private final AuditLog audit;

  UserAdministrationImpl(
      UserRepository users,
      RoleRepository roles,
      MembershipRepository memberships,
      Passwords passwords,
      AuditLog audit) {
    this.users = users;
    this.roles = roles;
    this.memberships = memberships;
    this.passwords = passwords;
    this.audit = audit;
  }

  @Override
  @Transactional(readOnly = true)
  public Page<MemberView> members(String q, PageQuery query) {
    UUID tenant = TenantContext.requireTenant();
    Specification<Membership> filter = Specs.equal("tenantUid", tenant);
    if (q != null && !q.isBlank()) {
      Set<UUID> matching =
          users.findAll(Specs.<User>containsAny(q, "name", "email")).stream()
              .map(User::getUid)
              .collect(Collectors.toSet());
      filter = filter.and(Specs.in("userUid", matching));
    }
    Page<Membership> page = Keyset.page(memberships, filter, query, Function.identity());
    Map<UUID, User> byUid = usersByUid(page.items().stream().map(Membership::getUserUid).toList());
    return page.map(
        membership -> AccessViews.member(membership, byUid.get(membership.getUserUid())));
  }

  @Override
  @Transactional
  public MemberView addMember(AddMemberCommand command) {
    Checks.required(command, "user");
    UUID tenant = TenantContext.requireTenant();
    String email = Checks.email(command.email(), "email");
    Role role = role(command.roleUid());
    Set<UUID> branches = branches(command.allBranches(), command.branchUids());
    User user =
        users
            .findByEmail(email)
            .orElseGet(
                () ->
                    users.save(
                        new User(
                            email,
                            Checks.text(command.name(), "name", 120),
                            passwords.hash(command.password(), "password"),
                            false)));
    if (memberships.existsByUserUidAndTenantUid(user.getUid(), tenant)) {
      throw Refusal.conflict("already_a_member", email + " is already a member of this tenant.");
    }
    Membership membership =
        memberships.save(new Membership(user.getUid(), role, command.allBranches(), branches));
    return AccessViews.member(membership, user);
  }

  @Override
  @Transactional
  public MemberView changeMember(UUID membershipUid, ChangeMemberCommand command) {
    Checks.required(command, "user");
    Membership membership =
        memberships
            .findByUidAndTenantUid(membershipUid, TenantContext.requireTenant())
            .orElseThrow(() -> Refusal.notFound("membership_not_found", "No such member."));
    Role role = role(command.roleUid());
    Set<UUID> branches = branches(command.allBranches(), command.branchUids());
    UUID self = ExecutionContext.current().map(frame -> frame.actor().uid()).orElse(null);
    if (!command.active() && membership.getUserUid().equals(self)) {
      throw Refusal.invalid(
          "cannot_deactivate_self", "You cannot deactivate your own membership.", "active");
    }
    membership.change(role, command.allBranches(), branches, command.active());
    return AccessViews.member(membership, users.findByUid(membership.getUserUid()).orElse(null));
  }

  @Override
  @Transactional(readOnly = true)
  public List<RoleView> roles() {
    return roles.findAllByOrderByNameAsc().stream().map(AccessViews::role).toList();
  }

  @Override
  @Transactional
  public RoleView createRole(RoleCommand command) {
    Checks.required(command, "role");
    String code = Checks.text(command.code(), "code", 32).toUpperCase();
    if (!code.matches(ROLE_CODE)) {
      throw Refusal.invalid(
          "invalid_code",
          "code must be letters, digits or underscores, such as NIGHT_SHIFT.",
          "code");
    }
    if (roles.existsByCode(code)) {
      throw Refusal.conflict("duplicate_code", "A role with code " + code + " exists.");
    }
    Role role =
        roles.save(
            new Role(
                code,
                Checks.text(command.name(), "name", 80),
                Checks.optionalText(command.description(), "description", 300),
                false,
                permissions(command.permissions())));
    audit.record(
        "ROLE_PERMISSIONS_CHANGED",
        "Role",
        role.getUid(),
        null,
        Map.of("code", code, "permissions", List.copyOf(role.getPermissions())));
    return AccessViews.role(role);
  }

  @Override
  @Transactional
  public RoleView changeRole(UUID roleUid, RoleCommand command) {
    Checks.required(command, "role");
    Role role = role(roleUid);
    String name = role.isSystem() ? role.getName() : Checks.text(command.name(), "name", 80);
    List<String> before = List.copyOf(role.getPermissions());
    Set<String> after = permissions(command.permissions());
    if (!Set.copyOf(before).equals(after)) {
      audit.record(
          "ROLE_PERMISSIONS_CHANGED",
          "Role",
          role.getUid(),
          Map.of("code", role.getCode(), "permissions", before),
          Map.of("code", role.getCode(), "permissions", List.copyOf(after)));
    }
    role.change(
        name,
        Checks.optionalText(command.description(), "description", 300),
        permissions(command.permissions()));
    return AccessViews.role(role);
  }

  @Override
  @Transactional
  public MemberView seedTenant(NewUserCommand administrator) {
    Checks.required(administrator, "admin");
    UUID tenant = TenantContext.requireTenant();
    Permissions.defaultRoles()
        .forEach(
            (code, name) -> {
              if (!roles.existsByCode(code)) {
                roles.save(new Role(code, name, null, true, Permissions.preset(code)));
              }
            });
    Role admin = roles.findByCode("ADMIN").orElseThrow();
    String email = Checks.email(administrator.email(), "admin.email");
    User user =
        users
            .findByEmail(email)
            .orElseGet(
                () ->
                    users.save(
                        new User(
                            email,
                            Checks.text(administrator.name(), "admin.name", 120),
                            passwords.hash(administrator.password(), "admin.password"),
                            false)));
    Optional<Membership> existing = memberships.findByUserUidAndTenantUid(user.getUid(), tenant);
    Membership membership =
        existing.orElseGet(
            () -> memberships.save(new Membership(user.getUid(), admin, true, List.of())));
    return AccessViews.member(membership, user);
  }

  @Override
  @Transactional(readOnly = true)
  public List<UserView> rootAdmins() {
    return users.findAllByRootAdminTrueOrderByNameAsc().stream().map(AccessViews::user).toList();
  }

  @Override
  @Transactional
  public UserView createRootAdmin(NewUserCommand command) {
    Checks.required(command, "rootAdmin");
    String email = Checks.email(command.email(), "email");
    if (users.findByEmail(email).isPresent()) {
      throw Refusal.conflict("duplicate_email", "A user with email " + email + " exists.");
    }
    User user =
        users.save(
            new User(
                email,
                Checks.text(command.name(), "name", 120),
                passwords.hash(command.password(), "password"),
                true));
    return AccessViews.user(user);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean anyRootAdmin() {
    return users.existsByRootAdminTrue();
  }

  @Override
  @Transactional(readOnly = true)
  public long userCount() {
    return users.count();
  }

  private Role role(UUID roleUid) {
    Checks.required(roleUid, "roleUid");
    return roles
        .findByUid(roleUid)
        .filter(role -> role.getTenantUid().equals(TenantContext.requireTenant()))
        .orElseThrow(() -> Refusal.invalid("role_not_found", "No such role.", "roleUid"));
  }

  private static Set<UUID> branches(boolean allBranches, Collection<UUID> branchUids) {
    if (allBranches) {
      return Set.of();
    }
    if (branchUids == null || branchUids.isEmpty()) {
      throw Refusal.invalid(
          "invalid_branch_uids", "Name at least one branch, or allow every branch.", "branchUids");
    }
    return new LinkedHashSet<>(branchUids);
  }

  private static Set<String> permissions(Collection<String> requested) {
    Set<String> permissions = new LinkedHashSet<>(requested == null ? List.of() : requested);
    for (String permission : permissions) {
      if (!Permissions.TENANT_ALL.contains(permission)) {
        throw Refusal.invalid(
            "invalid_permissions",
            permission + " is not a permission a tenant role may hold.",
            "permissions");
      }
    }
    return permissions;
  }

  private Map<UUID, User> usersByUid(Collection<UUID> uids) {
    if (uids.isEmpty()) {
      return Map.of();
    }
    return users.findAllByUidIn(uids).stream()
        .collect(Collectors.toMap(User::getUid, Function.identity()));
  }
}
