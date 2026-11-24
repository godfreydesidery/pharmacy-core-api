package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.UUID;

/**
 * Checks the manager who approves an action on someone else's screen: their sign-in (email or staff
 * login) and password, in the current pharmacy and branch.
 */
public interface Approvers {

  /**
   * The manager who may approve; {@code selfApproved} when it is the requester themselves, the
   * pharmacy's owner with nobody else able to approve ({@link #selfApprovalAllowed}).
   */
  record Approver(UUID userUid, String name, boolean selfApproved) {

    public Approver(UUID userUid, String name) {
      this(userUid, name, false);
    }
  }

  /**
   * The approver, if {@code login} and {@code password} are right and the person is an active
   * member here holding {@code approvals.grant} in this branch, and is not the one asking. Refuses
   * otherwise: {@code 422 invalid_credentials} (counted toward that account's lock), {@code 403
   * not_an_approver}, {@code 422 approver_is_requester} (unless {@link #selfApprovalAllowed}: the
   * owner alone then approves their own request, and the approver says {@code selfApproved}).
   */
  Approver verify(String login, String password, ClientInfo client);

  /**
   * Whether the person asking may approve their own request here (the owner's decision of 24 Nov
   * 2026): they are the pharmacy's owner (an active member with the built-in Administrator role),
   * and no other active member holding {@code approvals.grant} at this branch exists. Read from the
   * database each time, so a second approver ends it at once.
   */
  boolean selfApprovalAllowed();
}
