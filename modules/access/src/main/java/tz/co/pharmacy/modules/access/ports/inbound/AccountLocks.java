package tz.co.pharmacy.modules.access.ports.inbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Accounts locked after repeated failed sign-ins, as the current pharmacy's administrators see
 * them: who is locked until when, and the unlock that ends it early.
 */
public interface AccountLocks {

  /** A member of the current tenant whose account is locked now. */
  record LockedMember(UUID membershipUid, UUID userUid, Instant lockedUntil) {}

  /** The current tenant's members locked now. */
  List<LockedMember> locked();

  /** Unlocks the member's account and clears its failure count; 404 membership_not_found. */
  void unlock(UUID membershipUid);
}
