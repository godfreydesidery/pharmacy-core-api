package tz.co.pharmacy.delivery.edge;

import java.util.UUID;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;

/**
 * The branch rule of the edge for a branch a request names in its body or path rather than in
 * {@code X-Branch-Id}: the caller must be allowed to work there, as the edge checks the header (ADR
 * 0003, adversarial review ISO-9).
 */
public final class BranchAccess {

  private BranchAccess() {}

  /**
   * {@code branchUid} itself when the caller may act in it: a membership of every branch, one
   * listing it, or a root administrator; else 403 {@code branch_not_allowed}. Null passes through,
   * for the handler's own "required" check.
   */
  public static UUID requireAllowedBranch(UUID branchUid) {
    if (branchUid != null && !ExecutionContext.mayUseBranch(branchUid)) {
      throw notAllowed();
    }
    return branchUid;
  }

  /** 403 branch_not_allowed, the edge's refusal of a branch the caller may not use. */
  static Refusal notAllowed() {
    return Refusal.forbidden("branch_not_allowed", "You may not use this branch.");
  }
}
