package tz.co.pharmacy.modules.access.ports.inbound;

/**
 * Adding a person to the current pharmacy. A new email becomes a user and member at once; an email
 * that already has an account (in this or another pharmacy) is sent an invitation instead, so no
 * one is added to a pharmacy without accepting.
 */
public interface MemberEnrolment {

  sealed interface Outcome permits Added, Invited {}

  record Added(MemberView member) implements Outcome {}

  record Invited(InvitationView invitation) implements Outcome {}

  /** 409 already_a_member; 422 as {@link UserAdministration#addMember} or the invitation. */
  default Outcome add(AddMemberCommand command) {
    return add(command, true);
  }

  /**
   * As {@link #add(AddMemberCommand)}; without {@code directCreation} (the pharmacy may only
   * invite) even a new email is sent an invitation, and its password is ignored.
   */
  Outcome add(AddMemberCommand command, boolean directCreation);
}
