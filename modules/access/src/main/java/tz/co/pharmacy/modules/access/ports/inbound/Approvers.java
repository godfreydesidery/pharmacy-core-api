package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.UUID;

/**
 * Checks the manager who approves an action on someone else's screen: their sign-in (email or staff
 * login) and password, in the current pharmacy and branch.
 */
public interface Approvers {

  /** The manager who may approve. */
  record Approver(UUID userUid, String name) {}

  /**
   * The approver, if {@code login} and {@code password} are right and the person is an active
   * member here holding {@code approvals.grant} in this branch, and is not the one asking. Refuses
   * otherwise: {@code 422 invalid_credentials} (counted toward that account's lock), {@code 403
   * not_an_approver}, {@code 422 approver_is_requester}.
   */
  Approver verify(String login, String password, ClientInfo client);
}
