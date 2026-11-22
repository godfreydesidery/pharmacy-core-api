package tz.co.pharmacy.modules.access.ports.inbound;

/**
 * Adding a person to the current pharmacy by email: always an invitation, whether or not the email
 * already has an account, so no one joins without accepting, no admin sets the password of an email
 * account, and the answer says nothing of other pharmacies. (People with no email get a managed
 * login instead: {@link ManagedLogins}.)
 */
public interface MemberEnrolment {

  /** 409 already_a_member; 422 as the invitation. Any password in the command is ignored. */
  InvitationView add(AddMemberCommand command);
}
