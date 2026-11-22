package tz.co.pharmacy.modules.access.application;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tz.co.pharmacy.modules.access.ports.inbound.AddMemberCommand;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationService;
import tz.co.pharmacy.modules.access.ports.inbound.InvitationView;
import tz.co.pharmacy.modules.access.ports.inbound.InviteCommand;
import tz.co.pharmacy.modules.access.ports.inbound.MemberEnrolment;
import tz.co.pharmacy.platform.kernel.Checks;

@Service
class MemberEnrolmentImpl implements MemberEnrolment {

  private final InvitationService invitations;

  MemberEnrolmentImpl(InvitationService invitations) {
    this.invitations = invitations;
  }

  @Override
  @Transactional
  public InvitationView add(AddMemberCommand command) {
    Checks.required(command, "user");
    // The person joins when they accept: with a new account and their own password, or with the
    // account they already have, signed in.
    return invitations.invite(
        new InviteCommand(
            command.name(),
            command.email(),
            command.roleUid(),
            command.allBranches(),
            command.branchUids()));
  }
}
