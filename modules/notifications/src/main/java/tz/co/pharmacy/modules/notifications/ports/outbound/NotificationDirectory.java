package tz.co.pharmacy.modules.notifications.ports.outbound;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.modules.notifications.ports.inbound.Recipient;

/** The people of the current tenant (or the root administrators at platform scope). */
public interface NotificationDirectory {

  /**
   * Active members whose role grants {@code permission} and who may use {@code branchUid}; every
   * branch when it is null.
   */
  List<Recipient> usersWithPermission(String permission, UUID branchUid);

  Optional<Recipient> user(UUID userUid);
}
