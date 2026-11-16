package tz.co.pharmacy.platform.composition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.ports.inbound.MemberView;
import tz.co.pharmacy.modules.access.ports.inbound.RoleView;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;
import tz.co.pharmacy.modules.notifications.ports.outbound.NotificationDirectory;
import tz.co.pharmacy.platform.kernel.Page;
import tz.co.pharmacy.platform.kernel.PageQuery;
import tz.co.pharmacy.platform.persistence.TenantContext;

/**
 * Binds notifications' directory to access: the current tenant's active members and their roles'
 * permissions, or the root administrators at platform scope. A person's phone (from their profile)
 * lets SMS and WhatsApp reach them.
 */
@Component
class NotificationDirectoryAdapter implements NotificationDirectory {

  private static final int PAGE = 100;

  private final UserAdministration users;

  NotificationDirectoryAdapter(UserAdministration users) {
    this.users = users;
  }

  @Override
  public List<Recipient> usersWithPermission(String permission, UUID branchUid) {
    if (TenantContext.tenantUid().isEmpty()) {
      return List.of();
    }
    Map<UUID, RoleView> roles =
        users.roles().stream().collect(Collectors.toMap(RoleView::uid, Function.identity()));
    return members().stream()
        .filter(MemberView::active)
        .filter(
            member -> {
              RoleView role = roles.get(member.roleUid());
              return role != null && role.permissions().contains(permission);
            })
        .filter(
            member ->
                branchUid == null
                    || member.allBranches()
                    || (member.branchUids() != null && member.branchUids().contains(branchUid)))
        .map(NotificationDirectoryAdapter::recipient)
        .toList();
  }

  @Override
  public Optional<Recipient> user(UUID userUid) {
    if (TenantContext.tenantUid().isEmpty()) {
      return users.rootAdmins().stream()
          .filter(admin -> admin.uid().equals(userUid))
          .findFirst()
          .map(
              admin ->
                  new Recipient(admin.uid(), admin.name(), admin.email(), admin.phone(), null));
    }
    return members().stream()
        .filter(member -> member.userUid().equals(userUid))
        .findFirst()
        .map(NotificationDirectoryAdapter::recipient);
  }

  private List<MemberView> members() {
    List<MemberView> all = new ArrayList<>();
    PageQuery query = PageQuery.first(PAGE);
    while (true) {
      Page<MemberView> page = users.members(null, query);
      all.addAll(page.items());
      if (page.nextCursor() == null) {
        return all;
      }
      query = new PageQuery(page.nextCursor(), PAGE);
    }
  }

  private static Recipient recipient(MemberView member) {
    return new Recipient(member.userUid(), member.name(), member.email(), member.phone(), null);
  }
}
