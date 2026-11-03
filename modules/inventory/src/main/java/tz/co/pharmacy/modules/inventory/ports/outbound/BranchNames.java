package tz.co.pharmacy.modules.inventory.ports.outbound;

import java.util.UUID;

/** The name of a branch of the current tenant, or null when it has no such branch. */
public interface BranchNames {

  String name(UUID branchUid);
}
