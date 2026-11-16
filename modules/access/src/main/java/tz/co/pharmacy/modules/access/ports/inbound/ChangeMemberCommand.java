package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

/** {@code phone} is set only on a user who has none yet; people edit their own on their account. */
public record ChangeMemberCommand(
    UUID roleUid, boolean allBranches, List<UUID> branchUids, boolean active, String phone) {

  public ChangeMemberCommand(
      UUID roleUid, boolean allBranches, List<UUID> branchUids, boolean active) {
    this(roleUid, allBranches, branchUids, active, null);
  }
}
