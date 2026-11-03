package tz.co.pharmacy.modules.tenancy.ports.inbound;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The current tenant's branches. */
public interface BranchService {

  /** Head office first, then by name. */
  List<BranchView> list();

  Optional<BranchView> find(UUID branchUid);

  /** Refuses a code already used in the tenant: 409 duplicate_code. */
  BranchView create(BranchCommand command);

  /** Refuses to deactivate the head office: 422 head_office_required. */
  BranchView change(UUID branchUid, BranchCommand command, boolean active);
}
