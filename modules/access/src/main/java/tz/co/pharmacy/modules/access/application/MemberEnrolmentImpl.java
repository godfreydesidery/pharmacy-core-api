package tz.co.pharmacy.modules.access.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.AddMemberCommand;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationService;
import tz.co.pharmacy.modules.access.ports.inbound.InviteCommand;
import tz.co.pharmacy.modules.access.ports.inbound.MemberEnrolment;
import tz.co.pharmacy.modules.access.ports.inbound.UserAdministration;
import tz.co.pharmacy.platform.kernel.Checks;

@Service
class MemberEnrolmentImpl implements MemberEnrolment {

  private final UserAdministration administration;
  private final InvitationService invitations;
  private final UserRepository users;

  MemberEnrolmentImpl(
      UserAdministration administration, InvitationService invitations, UserRepository users) {
    this.administration = administration;
    this.invitations = invitations;
    this.users = users;
  }

  @Override
  @Transactional
  public Outcome add(AddMemberCommand command, boolean directCreation) {
    Checks.required(command, "user");
    String email = Checks.email(command.email(), "email");
    if (directCreation && users.findByEmail(email).isEmpty()) {
      return new Added(administration.addMember(command));
    }
    // The person already has an account: they join when they accept, with their own password.
    return new Invited(
        invitations.invite(
            new InviteCommand(
                command.name(),
                email,
                command.roleUid(),
                command.allBranches(),
                command.branchUids())));
  }
}
