package tz.co.pharmacy.modules.access.application;

import java.util.List;
import java.util.Set;
import tz.co.pharmacy.modules.access.domain.Membership;
import tz.co.pharmacy.modules.access.domain.Role;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.ports.inbound.MemberView;
import tz.co.pharmacy.modules.access.ports.inbound.MembershipGrant;
import tz.co.pharmacy.modules.access.ports.inbound.Permissions;
import tz.co.pharmacy.modules.access.ports.inbound.RoleView;
import tz.co.pharmacy.modules.access.ports.inbound.UserView;

/** Entity to view mappings of the access module. */
final class AccessViews {

  private AccessViews() {}

  static UserView user(User user) {
    return new UserView(
        user.getUid(),
        user.getName(),
        user.getEmail(),
        user.isRootAdmin(),
        user.isActive(),
        user.getLastSignInAt(),
        user.getCreatedAt(),
        user.getPhone(),
        user.isManaged(),
        user.getContactEmail(),
        user.hasVerifiedPhone());
  }

  static RoleView role(Role role) {
    return new RoleView(
        role.getUid(),
        role.getCode(),
        role.getName(),
        role.getDescription(),
        role.isSystem(),
        ordered(role.getPermissions()));
  }

  static MembershipGrant grant(Membership membership) {
    Role role = membership.getRole();
    return new MembershipGrant(
        membership.getUid(),
        membership.getTenantUid(),
        role.getUid(),
        role.getCode(),
        role.getName(),
        Set.copyOf(role.getPermissions()),
        membership.isAllBranches(),
        Set.copyOf(membership.getBranchUids()),
        membership.isActive());
  }

  /**
   * A member as the pharmacy sees them: as they are now while they work here, as they were when
   * they left afterwards. Activity is this pharmacy's only.
   */
  static MemberView member(Membership membership, User user) {
    Role role = membership.getRole();
    boolean former = membership.isFormer();
    return new MemberView(
        membership.getUid(),
        membership.getUserUid(),
        former ? membership.getFormerName() : user == null ? null : user.getName(),
        former ? membership.getFormerEmail() : user == null ? null : user.getEmail(),
        role.getUid(),
        role.getCode(),
        role.getName(),
        membership.isAllBranches(),
        List.copyOf(membership.getBranchUids()),
        membership.isActive(),
        membership.getLastActiveAt(),
        membership.getCreatedAt(),
        former ? membership.getFormerPhone() : user == null ? null : user.getPhone(),
        user != null && user.isManaged(),
        user == null ? null : user.getContactEmail(),
        user != null && user.isPasswordChangeRequired());
  }

  /** Permissions in catalogue order. */
  static List<String> ordered(Set<String> permissions) {
    return Permissions.ALL.stream().filter(permissions::contains).toList();
  }
}
