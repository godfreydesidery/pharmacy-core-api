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
   * Public, for a person with no account yet: makes it with the invited email, gives it the
   * membership and signs it in. 409 account_exists when the email has an account (it joins signed
   * in, {@link #join}); 422 invitation_not_open.
   */
  SignInOutcome accept(String token, String name, String password, ClientInfo client);

  /**
   * The signed-in account {@code userUid} accepts: it joins the pharmacy, whatever email it signs
   * in with. 409 already_a_member; 422 invitation_not_open; 422 managed_login_cannot_join (a
   * pharmacy's staff login works only there).
   */
  Joined join(String token, UUID userUid);

  /** The pharmacy joined and the role given. */
  record Joined(UUID tenantUid, String roleName) {}
}
