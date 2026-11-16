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
  Outcome add(AddMemberCommand command);
}
