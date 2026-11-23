package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tz.co.pharmacy.modules.access.domain.Invitation;
import tz.co.pharmacy.modules.access.persistence.InvitationRepository;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.outbound.UsersCap;
import tz.co.pharmacy.platform.kernel.ExecutionContext;

/**
 * The plan's users cap (adversarial review INT-6). A seat is an active member or an open
 * invitation, so many invitations cannot pass the cap. Each check takes the pharmacy's seats lock
 * (a transaction-scoped advisory lock) and counts in the transaction that adds the seat: two
 * requests at once, on one instance or two, cannot both take the last one. Call inside the writing
 * transaction, in the pharmacy's scope.
 */
@Component
class Seats {

  private final MembershipRepository memberships;
  private final InvitationRepository invitations;
  private final UserRepository users;
  private final UsersCap cap;
  private final Clock clock;

  Seats(
      MembershipRepository memberships,
      InvitationRepository invitations,
      UserRepository users,
      UsersCap cap,
      Clock clock) {
    this.memberships = memberships;
    this.invitations = invitations;
    this.users = users;
    this.cap = cap;
    this.clock = clock;
  }

  /**
   * One more seat: an invitation, a staff login, a member added or reactivated. Members and open
   * invitations count. A root administrator is never held back by a plan.
   */
  void requireNewSeat(UUID tenantUid) {
    if (ExecutionContext.rootAdmin()) {
      return;
    }
    memberships.lockSeats(tenantUid);
    long inUse =
        memberships.countByTenantUidAndActiveTrue(tenantUid)
            + invitations.countOpen(tenantUid, clock.instant());
    cap.requireRoom(tenantUid, inUse, 1);
  }

  /**
   * An invitation accepted: its seat was counted when it was sent, so only the members count here.
   * This still stops an admission over the cap after the plan was lowered, or of invitations sent
   * before invitations counted. An invitation a root administrator sent is never held back.
   */
  void requireAdmission(Invitation invitation) {
    UUID inviter = invitation.getCreatedByUid();
    if (inviter != null
        && users
            .findByUid(inviter)
            .filter(tz.co.pharmacy.modules.access.domain.User::isRootAdmin)
            .isPresent()) {
      return;
    }
    UUID tenantUid = invitation.getTenantUid();
    memberships.lockSeats(tenantUid);
    cap.requireRoom(tenantUid, memberships.countByTenantUidAndActiveTrue(tenantUid), 1);
  }

  /** Seats in use: active members and open invitations (the usage the plan page shows). */
  long inUse(UUID tenantUid) {
    return memberships.countByTenantUidAndActiveTrue(tenantUid)
        + invitations.countOpen(tenantUid, clock.instant());
  }
}
