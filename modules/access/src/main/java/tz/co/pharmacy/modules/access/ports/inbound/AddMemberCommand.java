package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

/** A new email creates the user with this password; an existing one keeps its password. */
public record AddMemberCommand(
    String name,
    String email,
    String password,
    UUID roleUid,
    boolean allBranches,
    List<UUID> branchUids,
    String phone) {

  public AddMemberCommand(
      String name,
      String email,
      String password,
      UUID roleUid,
      boolean allBranches,
      List<UUID> branchUids) {
    this(name, email, password, roleUid, allBranches, branchUids, null);
  }
}
