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
import tz.co.pharmacy.modules.access.persistence.SessionRepository;
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
  private final SessionRepository sessions;
  private final Seats seats;
  private final java.time.Clock clock;

  UserAdministrationImpl(
      UserRepository users,
      RoleRepository roles,
      MembershipRepository memberships,
      Passwords passwords,
      AuditLog audit,
      SessionRepository sessions,
      Seats seats,
      java.time.Clock clock) {
    this.users = users;
    this.roles = roles;
    this.memberships = memberships;
    this.passwords = passwords;
    this.sessions = sessions;
    this.seats = seats;
    this.clock = clock;
    this.audit = audit;
  }

  @Override
  @Transactional(readOnly = true)
  public java.util.Map<String, String> displayNames(java.util.Collection<String> references) {
    java.util.Map<String, String> names = new java.util.HashMap<>();
    if (references == null || references.isEmpty()) {
      return names;
    }
    java.util.Map<String, String> byLower = new java.util.HashMap<>();
    references.stream()
        .filter(r -> r != null && !r.isBlank())
        .forEach(r -> byLower.put(r.trim().toLowerCase(java.util.Locale.ROOT), r));
    if (byLower.isEmpty()) {
      return names;
    }
    List<User> found = users.findAllByEmails(byLower.keySet());
    Map<UUID, Membership> former = formerMembers(found.stream().map(User::getUid).toList());
    for (User user : found) {
      String reference = byLower.get(user.getEmail().toLowerCase(java.util.Locale.ROOT));
      displayName(user, former).ifPresent(name -> names.put(reference, name));
    }
    return names;
  }

  /**
   * The person's name as this pharmacy may show it: as they are now, or, once they left it, as it
   * kept them then (their name, else their email then); empty when it kept nothing.
   */
  private static Optional<String> displayName(User user, Map<UUID, Membership> former) {
    Membership left = former.get(user.getUid());
    if (left != null) {
      String kept = left.getFormerName();
      return Optional.ofNullable(kept == null || kept.isBlank() ? left.getFormerEmail() : kept);
    }
    String name = user.getName();
    return Optional.of(name == null || name.isBlank() ? user.getEmail() : name);
  }

  /** The people among {@code userUids} who no longer work in the current pharmacy, if any. */
  private Map<UUID, Membership> formerMembers(Collection<UUID> userUids) {
    Optional<UUID> tenant = TenantContext.tenantUid();
    if (tenant.isEmpty() || userUids.isEmpty()) {
      return Map.of();
    }
    return memberships.findAllByTenantUidAndUserUidIn(tenant.get(), userUids).stream()
        .filter(membership -> !membership.isActive())
        .collect(Collectors.toMap(Membership::getUserUid, Function.identity(), (a, b) -> a));
  }

  @Override
  @Transactional(readOnly = true)
  public long seatsInUse() {
    return seats.inUse(TenantContext.requireTenant());
  }

  @Override
  @Transactional(readOnly = true)
  public Map<UUID, String> displayNamesByUid(Collection<UUID> userUids) {
    Map<UUID, String> names = new java.util.HashMap<>();
    if (userUids == null) {
      return names;
    }
    List<UUID> uids = userUids.stream().filter(java.util.Objects::nonNull).distinct().toList();
    if (uids.isEmpty()) {
      return names;
    }
    List<User> found = users.findAllByUidIn(uids);
    Map<UUID, Membership> former = formerMembers(uids);
    for (User user : found) {
      displayName(user, former).ifPresent(name -> names.put(user.getUid(), name));
    }
    return names;
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
      // A former member is found by what the pharmacy kept, never by what they changed since.
      Specification<Membership> former =
          (root, criteria, cb) ->
              cb.and(cb.isFalse(root.get("active")), cb.isNotNull(root.get("formerEmail")));
      Specification<Membership> current = Specification.not(former);
      filter =
          filter.and(
              current
                  .and(Specs.in("userUid", matching))
                  .or(former.and(Specs.<Membership>containsAny(q, "formerName", "formerEmail")))
                  // The pharmacy's own details find a member too.
                  .or(Specs.<Membership>containsAny(q, "staffNumber", "jobTitle")));
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
    seats.requireNewSeat(tenant);
    Membership membership =
        memberships.save(new Membership(user.getUid(), role, command.allBranches(), branches));
    // A personal account's phone is the person's, verified by them (G9); an admin never sets it.
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
    // A missing "active" keeps the member as they are: it never reactivates anyone (INT-6).
    boolean active = command.active() == null ? membership.isActive() : command.active();
    UUID self = ExecutionContext.current().map(frame -> frame.actor().uid()).orElse(null);
    if (!active && membership.getUserUid().equals(self)) {
      throw Refusal.invalid(
          "cannot_deactivate_self", "You cannot deactivate your own membership.", "active");
    }
    boolean roleChanges = !membership.getRole().getUid().equals(role.getUid());
    if (roleChanges) {
      // Giving someone a role is granting its permissions: only ones the caller holds (AUTH-15).
      requireGrantable(role.getPermissions());
    }
    boolean wasActive = membership.isActive();
    boolean keepsPeople = active && managesPeople(role);
    if (wasActive && managesPeople(membership.getRole()) && !keepsPeople) {
      requireAnotherPeopleManager(membership);
    }
    if (!wasActive && active) {
      // Back at work: a seat of the plan's users cap again.
      seats.requireNewSeat(membership.getTenantUid());
    }
    membership.change(role, command.allBranches(), branches, active);
    User user = users.findByUid(membership.getUserUid()).orElse(null);
    String phone = phone(command.phone());
    if (user != null && user.isManagedBy(membership.getTenantUid())) {
      // The pharmacy's own login: it keeps the details, and a deactivated login is out at once.
      user.changeManagedContact(
          command.name() == null || command.name().isBlank()
              ? user.getName()
              : Checks.text(command.name(), "name", 120),
          phone,
          ManagedLoginsImpl.contactEmail(command.contactEmail()));
      if (wasActive && !active) {
        sessions.revokeAll(user.getUid(), clock.instant(), null);
      }
    }
    if (command.staff() != null) {
      staff(membership, command.staff());
    }
    if (wasActive && !active && user != null) {
      // From now on the pharmacy sees the person as they are at this moment.
      membership.left(user.getName(), user.getEmail(), user.getPhone());
    }
    return AccessViews.member(membership, user);
  }

  @Override
  @Transactional(readOnly = true)
  public List<RoleView> roles() {
    // Row-level security also lets a person see their own roles in other pharmacies (to sign in
    // there); this pharmacy's list names its tenant, so it shows only its own (ISO-7).
    return roles.findAllByTenantUidOrderByNameAsc(TenantContext.requireTenant()).stream()
        .map(AccessViews::role)
        .toList();
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
    if (roles.existsByTenantUidAndCode(TenantContext.requireTenant(), code)) {
      throw Refusal.conflict("duplicate_code", "A role with code " + code + " exists.");
    }
    requireGrantable(permissions(command.permissions()));
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
    if (role.isSystem() && "ADMIN".equals(role.getCode()) && !Set.copyOf(before).equals(after)) {
      // The owner's role always holds everything: editing it could lock every admin out (FRD-9).
      throw Refusal.invalid(
          "admin_role_fixed",
          "The Administrator role always has every permission; make a new role instead.",
          "permissions");
    }
    Set<String> added = new java.util.LinkedHashSet<>(after);
    before.forEach(added::remove);
    requireGrantable(added);
    if (managesPeople(role) && !after.contains(Permissions.USERS_MANAGE)) {
      // Taking "manage users" away from the only role that has it would leave nobody to add or
      // remove staff.
      boolean someoneElse =
          memberships.findAll().stream()
              .filter(other -> other.getTenantUid().equals(TenantContext.requireTenant()))
              .filter(Membership::isActive)
              .filter(other -> !other.getRole().getUid().equals(role.getUid()))
              .anyMatch(other -> managesPeople(other.getRole()));
      if (!someoneElse) {
        throw lastPeopleManager();
      }
    }
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
    // Flushed, so the view carries the version this change gave the row (its new ETag).
    roles.flush();
    return AccessViews.role(role);
  }

  /**
   * A permission may be granted (in a role, or by giving someone a role) only by someone who holds
   * it, so "manage users" is not a way to every other permission (OWASP ASVS V8.2, least
   * privilege). A root administrator holds them all.
   */
  static void requireGrantable(java.util.Collection<String> permissions) {
    if (ExecutionContext.rootAdmin()) {
      return;
    }
    for (String permission : permissions) {
      if (!ExecutionContext.permits(permission)) {
        throw Refusal.forbidden(
            "permission_not_held",
            "You cannot grant " + permission + ": your own role does not include it.");
      }
    }
  }

  static boolean managesPeople(Role role) {
    return role != null && role.getPermissions().contains(Permissions.USERS_MANAGE);
  }

  /** Someone other than {@code leaving} keeps an active membership that may manage people. */
  private void requireAnotherPeopleManager(Membership leaving) {
    boolean someoneElse =
        memberships.findAll().stream()
            .filter(other -> other.getTenantUid().equals(leaving.getTenantUid()))
            .filter(Membership::isActive)
            .filter(other -> !other.getUid().equals(leaving.getUid()))
            .anyMatch(other -> managesPeople(other.getRole()));
    if (!someoneElse) {
      throw lastPeopleManager();
    }
  }

  static Refusal lastPeopleManager() {
    return Refusal.conflict(
        "last_admin",
        "This would leave nobody who can manage the pharmacy's people: give the role to someone"
            + " else first.");
  }

  @Override
  @Transactional
  public tz.co.pharmacy.modules.access.ports.inbound.SeededTenant seedTenant(
      NewUserCommand administrator) {
    Checks.required(administrator, "admin");
    UUID tenant = TenantContext.requireTenant();
    Permissions.defaultRoles()
        .forEach(
            (code, name) -> {
              if (!roles.existsByTenantUidAndCode(tenant, code)) {
                roles.save(new Role(code, name, null, true, Permissions.preset(code)));
              }
            });
    Role admin = roles.findByTenantUidAndCode(tenant, "ADMIN").orElseThrow();
    String email = Checks.email(administrator.email(), "admin.email");
    Optional<User> found = users.findByEmail(email);
    if (found.isPresent()) {
      Optional<Membership> existing =
          memberships.findByUserUidAndTenantUid(found.get().getUid(), tenant);
      return new tz.co.pharmacy.modules.access.ports.inbound.SeededTenant(
          existing.map(membership -> AccessViews.member(membership, found.get())).orElse(null),
          admin.getUid());
    }
    User user =
        users.save(
            new User(
                email,
                Checks.text(administrator.name(), "admin.name", 120),
                passwords.hash(administrator.password(), "admin.password"),
                false));
    Membership membership = memberships.save(new Membership(user.getUid(), admin, true, List.of()));
    return new tz.co.pharmacy.modules.access.ports.inbound.SeededTenant(
        AccessViews.member(membership, user), admin.getUid());
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
  @Transactional
  public UserView setRootAdminActive(UUID userUid, boolean active) {
    User user = rootAdmin(userUid);
    if (!active) {
      guardRootChange(user);
      sessions.revokeAll(user.getUid(), clock.instant(), null);
    }
    user.activate(active);
    return AccessViews.user(user);
  }

  @Override
  @Transactional
  public void removeRootAdmin(UUID userUid) {
    User user = rootAdmin(userUid);
    guardRootChange(user);
    user.revokeRoot();
    if (memberships.findAllByUserUid(user.getUid()).isEmpty()) {
      // Nothing left to sign in to: the account is closed and its sessions end.
      user.activate(false);
      sessions.revokeAll(user.getUid(), clock.instant(), null);
    }
  }

  @Override
  @Transactional
  public UserView changeOwnProfile(String name, String phone) {
    User user = self();
    String named = Checks.text(name, "name", 120);
    if (user.isManaged()) {
      // A staff login's phone is a contact detail.
      user.changeProfile(named, phone(phone));
      return AccessViews.user(user);
    }
    // A person's phone changes only through verification (a code sent to it).
    String given = phone == null || phone.isBlank() ? null : Phones.normalise(phone);
    if (given != null && !given.equals(user.getPhone())) {
      throw Refusal.invalid(
          "phone_needs_verification",
          "Add or change your phone on the security page, with the code sent to it.",
          "phone");
    }
    user.rename(named);
    return AccessViews.user(user);
  }

  @Override
  @Transactional(readOnly = true)
  public UserView me() {
    return AccessViews.user(self());
  }

  private User self() {
    UUID uid =
        ExecutionContext.current()
            .map(frame -> frame.actor().uid())
            .orElseThrow(() -> Refusal.unauthenticated("unauthenticated", "Sign in first."));
    return users
        .findByUid(uid)
        .orElseThrow(() -> Refusal.notFound("user_not_found", "No such user."));
  }

  private User rootAdmin(UUID userUid) {
    return users
        .findByUid(Checks.required(userUid, "userUid"))
        .filter(User::isRootAdmin)
        .orElseThrow(() -> Refusal.notFound("root_admin_not_found", "No such root administrator."));
  }

  /** Never yourself, never the last active root administrator. */
  private void guardRootChange(User user) {
    UUID self = ExecutionContext.current().map(frame -> frame.actor().uid()).orElse(null);
    if (user.getUid().equals(self)) {
      throw Refusal.invalid(
          "cannot_change_self", "You cannot deactivate or remove yourself.", "userUid");
    }
    long othersActive =
        users.findAllByRootAdminTrueOrderByNameAsc().stream()
            .filter(User::isActive)
            .filter(other -> !other.getUid().equals(user.getUid()))
            .count();
    if (user.isActive() && othersActive == 0) {
      throw Refusal.invalid(
          "last_root_admin",
          "The platform needs at least one active root administrator.",
          "userUid");
    }
  }

  /** E.164 (+ then 8 to 15 digits); blank is none. Spaces and dashes are ignored. */
  /** Checks and applies the pharmacy's own details of a member (G15). */
  private void staff(
      Membership membership, tz.co.pharmacy.modules.access.ports.inbound.StaffDetails d) {
    String number = Checks.optionalText(d.staffNumber(), "staffNumber", 40);
    if (number != null
        && memberships.existsByTenantUidAndStaffNumberIgnoreCaseAndUidNot(
            membership.getTenantUid(), number, membership.getUid())) {
      throw Refusal.conflict(
          "duplicate_staff_number", "Another member already has staff number " + number + ".");
    }
    String workEmail =
        d.workEmail() == null || d.workEmail().isBlank()
            ? null
            : Checks.email(d.workEmail(), "workEmail");
    String workPhone =
        d.workPhone() == null || d.workPhone().isBlank() ? null : Phones.normalise(d.workPhone());
    membership.changeStaff(
        number, Checks.optionalText(d.jobTitle(), "jobTitle", 80), workPhone, workEmail);
  }

  static String phone(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    String phone = value.replaceAll("[\\s-]", "");
    if (!phone.matches("^\\+[1-9][0-9]{7,14}$")) {
      throw Refusal.invalid(
          "invalid_phone",
          "Enter the phone in international form, such as +255754000111.",
          "phone");
    }
    return phone;
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
        .findByUidAndTenantUid(roleUid, TenantContext.requireTenant())
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
