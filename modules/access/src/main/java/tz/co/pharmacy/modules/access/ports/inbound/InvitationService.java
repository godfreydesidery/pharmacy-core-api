package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.UUID;

/** Invitations to join the current tenant, sent by email and valid for 7 days. */
public interface InvitationService {

  /** 409 already_a_member; 422 role_not_found; 422 invalid_branch_uids. */
  InvitationView invite(InviteCommand command);

  /** The tenant's invitations, newest first. */
  List<InvitationView> list();

  /** 404 invitation_not_found. */
  void revoke(UUID invitationUid);

  /** Public: 404 invitation_not_found. */
  InvitationPreview preview(String token);

  /**
   * Public: makes the user (or, for an existing account, checks its password), gives them the
   * membership and signs them in. 422 invitation_not_open; 401 invalid_credentials.
   */
  SignInOutcome accept(String token, String name, String password, ClientInfo client);
}
