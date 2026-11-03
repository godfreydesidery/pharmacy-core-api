package tz.co.pharmacy.modules.reporting.application;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The branch condition of a read-model query: none for every branch (null), no row for an empty
 * set, otherwise {@code branch_uid in (...)}. Row-level security already holds the query to the
 * tenant.
 */
final class BranchFilter {

  /** Stands in for a fact without a branch, so the read models' keys stay non-null. */
  static final UUID NO_BRANCH = new UUID(0L, 0L);

  private BranchFilter() {}

  static String clause(Set<UUID> branchUids, List<Object> arguments) {
    if (branchUids == null) {
      return "";
    }
    if (branchUids.isEmpty()) {
      return " and false";
    }
    arguments.addAll(branchUids);
    return " and branch_uid in ("
        + String.join(", ", Collections.nCopies(branchUids.size(), "?"))
        + ")";
  }

  static UUID orNone(UUID branchUid) {
    return branchUid == null ? NO_BRANCH : branchUid;
  }
}
