package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.access.domain.Session;
import tz.co.pharmacy.modules.access.domain.SignInChallenge;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.MembershipRepository;
import tz.co.pharmacy.modules.access.persistence.PasskeyRepository;
import tz.co.pharmacy.modules.access.persistence.SessionRepository;
import tz.co.pharmacy.modules.access.persistence.SignInChallengeRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.AuthenticationService;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.inbound.MembershipGrant;
import tz.co.pharmacy.modules.access.ports.inbound.Principal;
import tz.co.pharmacy.modules.access.ports.inbound.SessionView;
import tz.co.pharmacy.modules.access.ports.inbound.SignInOutcome;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.Refusal;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Sign-in and sessions. The writes run in transactions this service opens itself, inside a frame
 * that names the user, so every row records who signed in, even on the anonymous sign-in route. A
 * wrong password counts towards the account's lockout; a right one opens a session, or a
 * second-factor challenge when the user has two-factor on.
 */
@Service
class AuthenticationServiceImpl implements AuthenticationService {

  static final Duration CHALLENGE_LENGTH = Duration.ofMinutes(5);
  private static final Duration SEEN_EVERY = Duration.ofMinutes(5);

  private final UserRepository users;
  private final SessionRepository sessions;
  private final MembershipRepository memberships;
  private final SignInChallengeRepository challenges;
  private final PasskeyRepository passkeys;
  private final Passwords passwords;
  private final SignInGuard guard;
  private final SessionIssuer issuer;
  private final AuditLog audit;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;

  private final Clock clock;

  AuthenticationServiceImpl(
      UserRepository users,
      SessionRepository sessions,
      MembershipRepository memberships,
      SignInChallengeRepository challenges,
      PasskeyRepository passkeys,
      Passwords passwords,
      SignInGuard guard,
      SessionIssuer issuer,
      AuditLog audit,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.clock = clock;
    this.users = users;
    this.sessions = sessions;
    this.memberships = memberships;
    this.challenges = challenges;
    this.passkeys = passkeys;
    this.passwords = passwords;
    this.guard = guard;
    this.issuer = issuer;
    this.audit = audit;
    this.writes = new TransactionTemplate(transactionManager);
    this.reads = new TransactionTemplate(transactionManager);
    this.reads.setReadOnly(true);
  }

  @Override
  public SignInOutcome signIn(String email, String password, ClientInfo client) {
    ClientInfo from = client == null ? ClientInfo.UNKNOWN : client;
    String normalised = email == null ? "" : email.trim().toLowerCase();
    Instant now = clock.instant();
    guard.checkClient(from.ip(), now);
    Optional<User> found = reads.execute(status -> users.findByEmail(normalised));
    User user = found == null ? null : found.orElse(null);
    guard.checkAccount(user, normalised, now);
    boolean matches = passwords.matches(password, user == null ? null : user.getPasswordHash());
    if (user == null || !matches || !user.isActive()) {
      boolean locked = recordFailure(user, normalised, from, now, "password");
      if (locked) {
        throw guard.locked(guard.lockFor());
      }
      throw Refusal.unauthenticated("invalid_credentials", "The email or password is wrong.");
    }
    if (user.isTwoFactorEnabled()) {
      return challenge(user, from);
    }
    return new SignInOutcome.Signed(issuer.issue(user, from, "PASSWORD"));
  }

  /** Counts a failed sign-in and audits it; true when it locked the account. */
  boolean recordFailure(User user, String email, ClientInfo from, Instant at, String factor) {
    Map<String, Object> details = new LinkedHashMap<>();
    details.put("email", email);
    details.put("ip", from.ip());
    details.put("factor", factor);
    if (user == null) {
      boolean locked = guard.failed(null, email, from.ip(), at);
      audit.record("SIGN_IN_FAILED", "User", null, null, details);
      return locked;
    }
    Boolean locked =
        ExecutionContext.callAs(
            SessionIssuer.userFrame(user),
            () ->
                writes.execute(
                    status -> {
                      User row = users.findByUid(user.getUid()).orElse(user);
                      boolean lockedNow = guard.failed(row, email, from.ip(), at);
                      audit.record("SIGN_IN_FAILED", "User", user.getUid(), null, details);
                      if (lockedNow) {
                        audit.record(
                            "ACCOUNT_LOCKED",
                            "User",
                            user.getUid(),
                            null,
                            Map.of("lockedUntil", String.valueOf(row.getLockedUntil())));
                      }
                      return lockedNow;
                    }));
    return Boolean.TRUE.equals(locked);
  }

  private SignInOutcome challenge(User user, ClientInfo from) {
    String token = "pch_" + Tokens.newToken().substring(4);
    Instant expiresAt = clock.instant().plus(CHALLENGE_LENGTH);
    Boolean hasPasskey =
        ExecutionContext.callAs(
            SessionIssuer.userFrame(user),
            () ->
                writes.execute(
                    status -> {
                      challenges.save(
                          new SignInChallenge(
                              Tokens.hash(token),
                              user.getUid(),
                              expiresAt,
                              SessionIssuer.trim(from.ip(), 64),
                              SessionIssuer.trim(from.userAgent(), 400)));
                      return passkeys.existsByUserUid(user.getUid());
                    }));
    List<String> methods = new ArrayList<>(List.of("TOTP", "RECOVERY_CODE"));
    if (Boolean.TRUE.equals(hasPasskey)) {
      methods.add("PASSKEY");
    }
    return new SignInOutcome.ChallengeRequired(token, List.copyOf(methods), expiresAt);
  }

  @Override
  public Optional<Principal> authenticate(String token) {
    if (token == null || token.isBlank()) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    Optional<Live> live =
        reads.execute(
            status ->
                sessions
                    .findByTokenHash(Tokens.hash(token.trim()))
                    .filter(session -> session.liveAt(now))
                    .flatMap(
                        session ->
                            users
                                .findByUid(session.getUserUid())
                                .filter(User::isActive)
                                .map(user -> new Live(session, issuer.principal(user)))));
    if (live == null || live.isEmpty()) {
      return Optional.empty();
    }
    Session session = live.get().session();
    if (session.getLastSeenAt() == null
        || session.getLastSeenAt().isBefore(now.minus(SEEN_EVERY))) {
      writes.executeWithoutResult(status -> sessions.touch(session.getId(), now));
    }
    return Optional.of(live.get().principal());
  }

  private record Live(Session session, Principal principal) {}

  @Override
  public void signOut(String token) {
    if (token == null || token.isBlank()) {
      return;
    }
    writes.executeWithoutResult(
        status ->
            sessions
                .findByTokenHash(Tokens.hash(token.trim()))
                .ifPresent(
                    session -> {
                      session.revoke(clock.instant());
                      audit.record("SIGN_OUT", "User", session.getUserUid(), null, null);
                    }));
  }

  @Override
  public void changePassword(UUID userUid, String currentPassword, String newPassword) {
    changePassword(userUid, currentPassword, newPassword, null);
  }

  @Override
  public void changePassword(
      UUID userUid, String currentPassword, String newPassword, String currentToken) {
    String hash = passwords.hash(newPassword, "newPassword");
    writes.executeWithoutResult(
        status -> {
          User user =
              users
                  .findByUid(userUid)
                  .orElseThrow(() -> Refusal.notFound("user_not_found", "No such user."));
          if (!passwords.matches(currentPassword, user.getPasswordHash())) {
            throw Refusal.invalid(
                "invalid_current_password", "The current password is wrong.", "currentPassword");
          }
          user.changePassword(hash);
          int revoked =
              currentToken == null
                  ? 0
                  : sessions.revokeAll(userUid, clock.instant(), Tokens.hash(currentToken.trim()));
          audit.record(
              "PASSWORD_CHANGED", "User", userUid, null, Map.of("otherSessionsRevoked", revoked));
        });
  }

  @Override
  public List<SessionView> sessions(UUID userUid, String currentToken) {
    String current = currentToken == null ? null : Tokens.hash(currentToken.trim());
    List<SessionView> views =
        reads.execute(
            status ->
                sessions.findLive(userUid, clock.instant()).stream()
                    .map(
                        session ->
                            new SessionView(
                                session.getUid(),
                                session.getMethod(),
                                session.getIp(),
                                session.getUserAgent(),
                                session.getCreatedAt(),
                                session.getLastSeenAt(),
                                session.getExpiresAt(),
                                session.getTokenHash().equals(current)))
                    .toList());
    return views == null ? List.of() : views;
  }

  @Override
  public void revokeSession(UUID userUid, UUID sessionUid) {
    writes.executeWithoutResult(
        status -> {
          Session session =
              sessions
                  .findByUid(sessionUid)
                  .filter(found -> found.getUserUid().equals(userUid))
                  .orElseThrow(() -> Refusal.notFound("session_not_found", "No such session."));
          session.revoke(clock.instant());
          audit.record(
              "SESSION_REVOKED", "User", userUid, null, Map.of("session", sessionUid.toString()));
        });
  }

  @Override
  public int revokeOtherSessions(UUID userUid, String currentToken) {
    if (currentToken == null || currentToken.isBlank()) {
      throw Refusal.invalid("session_required", "Sign in with a session token first.");
    }
    Integer revoked =
        writes.execute(
            status -> {
              int count =
                  sessions.revokeAll(userUid, clock.instant(), Tokens.hash(currentToken.trim()));
              audit.record(
                  "OTHER_SESSIONS_REVOKED", "User", userUid, null, Map.of("revoked", count));
              return count;
            });
    return revoked == null ? 0 : revoked;
  }

  @Override
  public List<MembershipGrant> memberships(UUID userUid) {
    List<MembershipGrant> grants =
        TenantContext.callAsUser(
            userUid,
            () ->
                reads.execute(
                    status ->
                        memberships.findAllByUserUid(userUid).stream()
                            .map(AccessViews::grant)
                            .toList()));
    return grants == null ? List.of() : grants;
  }
}
