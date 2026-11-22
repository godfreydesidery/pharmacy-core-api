package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

/**
 * {@code phone} is set only on a user who has none yet; people edit their own on their account. A
 * managed login of this pharmacy is the exception: its {@code name}, {@code phone} and {@code
 * contactEmail} are the pharmacy's to keep (a null name keeps it).
 */
public record ChangeMemberCommand(
    UUID roleUid,
    boolean allBranches,
    List<UUID> branchUids,
    boolean active,
    String phone,
    String name,
    String contactEmail) {

  public ChangeMemberCommand(
      UUID roleUid, boolean allBranches, List<UUID> branchUids, boolean active, String phone) {
    this(roleUid, allBranches, branchUids, active, phone, null, null);
  }

  public ChangeMemberCommand(
      UUID roleUid, boolean allBranches, List<UUID> branchUids, boolean active) {
    this(roleUid, allBranches, branchUids, active, null, null, null);
  }
}
