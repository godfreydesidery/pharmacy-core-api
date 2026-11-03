package tz.co.pharmacy.modules.access.ports.inbound;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import tz.co.pharmacy.platform.kernel.Refusal;

/** Sign-in, sessions and the signed-in user's memberships. */
public interface AuthenticationService {

  /**
   * Checks the password. Returns a session, or a second-factor challenge when the user has
   * two-factor on. 401 invalid_credentials for an unknown email, a wrong password or an inactive
   * user; 423 account_locked (with retryAfterSeconds) after 5 failures in 15 minutes, for 15
   * minutes.
   */
  SignInOutcome signIn(String email, String password, ClientInfo client);

  /**
   * The password-only sign-in of a user without two-factor; 403 two_factor_required otherwise. Kept
   * for callers that cannot answer a challenge.
   */
  default SignInResult signIn(String email, String password) {
    return switch (signIn(email, password, ClientInfo.UNKNOWN)) {
      case SignInOutcome.Signed signed -> signed.session();
      case SignInOutcome.ChallengeRequired challenge ->
          throw Refusal.forbidden(
              "two_factor_required", "This account signs in with a second factor.");
    };
  }

  /**
   * The session's user; empty when the token is unknown, expired or revoked, or the user inactive.
   */
  Optional<Principal> authenticate(String token);

  void signOut(String token);

  /** 422 invalid_current_password; 422 weak_password. */
  void changePassword(UUID userUid, String currentPassword, String newPassword);

  /**
   * As {@link #changePassword(UUID, String, String)}, and revokes the user's other sessions,
   * keeping the one with {@code currentToken}.
   */
  void changePassword(
      UUID userUid, String currentPassword, String newPassword, String currentToken);

  /** The user's live sessions, newest first; the one with {@code currentToken} is marked. */
  List<SessionView> sessions(UUID userUid, String currentToken);

  /** Revokes one of the user's own sessions; 404 session_not_found. */
  void revokeSession(UUID userUid, UUID sessionUid);

  /**
   * The user's memberships in every tenant. Call it outside a transaction: it reads them as the
   * user, in its own transaction.
   */
  List<MembershipGrant> memberships(UUID userUid);
}
