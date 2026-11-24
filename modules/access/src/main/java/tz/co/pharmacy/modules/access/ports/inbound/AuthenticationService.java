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

  /**
   * A ticket for the live notification stream of session {@code sessionUid}: EventSource cannot
   * send an Authorization header, and a session token in a URL ends up in access logs and browser
   * history. The ticket is sealed (AES-GCM) with the session and a 60-second expiry, so it holds no
   * token, opens nothing else, and works on any instance (adversarial review AUTH-11).
   */
  String streamTicket(UUID sessionUid);

  /** The signed-in user of a live stream ticket, as {@link #authenticate} gives for a token. */
  Optional<Principal> authenticateStreamTicket(String ticket);

  /** The session still lets its user in: not revoked, expired or idle, and the user is active. */
  boolean sessionLive(UUID sessionUid);

  /**
   * The person used the app (a key, a click, a touch): the inactivity clock restarts, with the
   * limit of the pharmacy they work in ({@code pharmacyIdleMinutes}; null before one is chosen).
   * Requests alone never restart it. 401 session_expired when the session is no longer live.
   */
  SessionActivity recordActivity(String token, Integer pharmacyIdleMinutes);

  /**
   * A request of {@code principal}'s session works in a pharmacy whose inactivity limit is {@code
   * pharmacyIdleMinutes} (AUTH-17): the limit is taken from each request's pharmacy on the server,
   * whatever pharmacy the app names when it reports activity. 401 session_idle when the session has
   * been unused for longer than that limit (and it ends); a stricter limit than the session's
   * becomes the session's. Nothing for a root administrator, who keeps the platform's limit.
   */
  void workingUnderLimit(Principal principal, Integer pharmacyIdleMinutes);

  default void signOut(String token) {
    signOut(token, false);
  }

  /**
   * Ends the session. {@code idleTimeout}: the app signed the person out after its inactivity
   * limit, audited as such (a SIGN_OUT with reason IDLE).
   */
  void signOut(String token, boolean idleTimeout);

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

  /** Signs the user out everywhere but the session with {@code currentToken}; how many ended. */
  int revokeOtherSessions(UUID userUid, String currentToken);

  /**
   * The user's memberships in every tenant. Call it outside a transaction: it reads them as the
   * user, in its own transaction.
   */
  List<MembershipGrant> memberships(UUID userUid);
}
