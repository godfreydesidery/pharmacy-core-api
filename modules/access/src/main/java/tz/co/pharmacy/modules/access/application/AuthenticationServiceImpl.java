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
import tz.co.pharmacy.modules.access.ports.inbound.SessionActivity;
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
  private static final String VERSIONED = "v2:";

  private final UserRepository users;
  private final SessionRepository sessions;
  private final tz.co.pharmacy.modules.access.persistence.PasswordResetRepository resets;
  private final SecretBox secrets;

  /** How long a stream ticket opens the notification stream. */
  static final Duration STREAM_TICKET = Duration.ofSeconds(60);

  private final MembershipRepository memberships;
  private final SignInChallengeRepository challenges;
  private final PasskeyRepository passkeys;
  private final Passwords passwords;
  private final SignInGuard guard;
  private final SessionIssuer issuer;
  private final IdlePolicy idle;
  private final tz.co.pharmacy.platform.persistence.TenantSwitch tenantSwitch;
  private final AuditLog audit;
  private final org.springframework.beans.factory.ObjectProvider<
          tz.co.pharmacy.modules.access.ports.outbound.AccessNotices>
      notices;
  private final TransactionTemplate writes;
  private final TransactionTemplate reads;

  private final Clock clock;

  AuthenticationServiceImpl(
      UserRepository users,
      SessionRepository sessions,
      MembershipRepository memberships,
      SignInChallengeRepository challenges,
      PasskeyRepository passkeys,
      tz.co.pharmacy.modules.access.persistence.PasswordResetRepository resets,
      SecretBox secrets,
      Passwords passwords,
      SignInGuard guard,
      SessionIssuer issuer,
      IdlePolicy idle,
      tz.co.pharmacy.platform.persistence.TenantSwitch tenantSwitch,
      AuditLog audit,
      org.springframework.beans.factory.ObjectProvider<
              tz.co.pharmacy.modules.access.ports.outbound.AccessNotices>
          notices,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.clock = clock;
    this.notices = notices;
    this.idle = idle;
    this.tenantSwitch = tenantSwitch;
    this.users = users;
    this.sessions = sessions;
    this.resets = resets;
    this.secrets = secrets;
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
    if (user != null && matches && !user.isActive() && user.getLinkedToUid() != null) {
      // Only someone who knows the login's password learns where it went.
      throw Refusal.unauthenticated(
          "login_linked",
          "This staff login is now part of your own account: sign in with your email.");
    }
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
    // The client's count first, on a connection of its own, so the transaction below holds one.
    guard.clientFailed(from.ip(), at);
    Boolean locked =
        ExecutionContext.callAs(
            SessionIssuer.userFrame(user),
            () ->
                writes.execute(
                    status -> {
                      User row = users.findByUid(user.getUid()).orElse(user);
                      boolean lockedNow = guard.accountFailed(row, at);
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
                                .map(
                                    user ->
                                        new Live(session, user, issuer.principal(user, session)))));
    if (live == null || live.isEmpty()) {
      return Optional.empty();
    }
    Session session = live.get().session();
    if (session.idleAt(now)) {
      issuer.endIdle(session.getId(), live.get().user(), session.getIdleMinutes());
      throw idleRefusal(session.getIdleMinutes());
    }
    if (session.getLastSeenAt() == null
        || session.getLastSeenAt().isBefore(now.minus(SEEN_EVERY))) {
      writes.executeWithoutResult(status -> sessions.touch(session.getId(), now));
    }
    return Optional.of(live.get().principal());
  }

  private record Live(Session session, User user, Principal principal) {}

  @Override
  public String streamTicket(UUID sessionUid) {
    if (sessionUid == null) {
      throw Refusal.unauthenticated("unauthenticated", "Sign in to open the stream.");
    }
    String plain = sessionUid + "|" + clock.instant().plus(STREAM_TICKET).getEpochSecond();
    // URL-safe (an EventSource cannot send headers). A value under a named key travels as its
    // text, which names the key; one under the legacy key as its raw bytes, as before, so either
    // release reads the other's tickets during a rolling deploy.
    String sealed = secrets.seal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    byte[] body =
        sealed.startsWith(VERSIONED)
            ? sealed.getBytes(java.nio.charset.StandardCharsets.US_ASCII)
            : java.util.Base64.getDecoder().decode(sealed);
    return "pst_" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(body);
  }

  @Override
  public Optional<Principal> authenticateStreamTicket(String ticket) {
    if (ticket == null || !ticket.startsWith("pst_")) {
      return Optional.empty();
    }
    String[] parts;
    try {
      byte[] decoded = java.util.Base64.getUrlDecoder().decode(ticket.substring(4));
      String text = new String(decoded, java.nio.charset.StandardCharsets.US_ASCII);
      String sealed =
          text.startsWith(VERSIONED) ? text : java.util.Base64.getEncoder().encodeToString(decoded);
      parts =
          new String(secrets.open(sealed), java.nio.charset.StandardCharsets.UTF_8).split("\\|");
    } catch (RuntimeException forged) {
      return Optional.empty();
    }
    Instant now = clock.instant();
    if (parts.length != 2 || Long.parseLong(parts[1]) < now.getEpochSecond()) {
      return Optional.empty();
    }
    UUID sessionUid = UUID.fromString(parts[0]);
    return reads.execute(
        status ->
            sessions
                .findByUid(sessionUid)
                .filter(session -> session.liveAt(now) && !session.idleAt(now))
                .flatMap(
                    session ->
                        users
                            .findByUid(session.getUserUid())
                            .filter(User::isActive)
                            .map(user -> issuer.principal(user, session))));
  }

  @Override
  public boolean sessionLive(UUID sessionUid) {
    if (sessionUid == null) {
      return false;
    }
    Instant now = clock.instant();
    Boolean live =
        reads.execute(
            status ->
                sessions
                    .findByUid(sessionUid)
                    .filter(session -> session.liveAt(now) && !session.idleAt(now))
                    .flatMap(session -> users.findByUid(session.getUserUid()))
                    .map(User::isActive)
                    .orElse(false));
    return Boolean.TRUE.equals(live);
  }

  /** 401 session_idle, so the app can tell the person why they have to sign in again. */
  static Refusal idleRefusal(Integer idleMinutes) {
    return Refusal.unauthenticated(
            "session_idle",
            "You were signed out after "
                + idleMinutes
                + " minutes without using the app; sign in again.")
        .with("idleMinutes", idleMinutes);
  }

  @Override
  public SessionActivity recordActivity(String token, Integer pharmacyIdleMinutes) {
    Instant now = clock.instant();
    Live live = liveSession(token, now);
    if (live == null) {
      throw Refusal.unauthenticated("session_expired", "Your session has expired; sign in again.");
    }
    Session session = live.session();
    Integer offered = idle.minutesFor(live.user().isRootAdmin(), pharmacyIdleMinutes);
    boolean fromPharmacy =
        offered != null && pharmacyIdleMinutes != null && !live.user().isRootAdmin();
    Integer minutes =
        writes.execute(
            status -> {
              sessions.markActive(session.getId(), now, offered, fromPharmacy);
              return sessions
                  .findById(session.getId())
                  .map(Session::getIdleMinutes)
                  .orElse(offered);
            });
    return new SessionActivity(
        minutes,
        minutes == null ? null : now.plus(Duration.ofMinutes(minutes)),
        session.getExpiresAt());
  }

  @Override
  public void workingUnderLimit(Principal principal, Integer pharmacyIdleMinutes) {
    if (principal == null || principal.rootAdmin() || principal.sessionUid() == null) {
      return;
    }
    Integer limit = idle.minutesFor(false, pharmacyIdleMinutes);
    Integer current = principal.idleMinutes();
    if (limit == null || current == null || pharmacyIdleMinutes == null) {
      // No limit at all (switched off), or none on this session.
      return;
    }
    Instant now = clock.instant();
    Instant activeAt = principal.activeAt();
    if (activeAt != null && !activeAt.plus(Duration.ofMinutes(limit)).isAfter(now)) {
      // Unused for longer than this pharmacy allows: the session ends, as at its own limit.
      Optional<Live> live =
          reads.execute(
              status ->
                  sessions
                      .findByUid(principal.sessionUid())
                      .flatMap(
                          session ->
                              users
                                  .findByUid(session.getUserUid())
                                  .map(user -> new Live(session, user, principal))));
      if (live != null && live.isPresent()) {
        issuer.endIdle(live.get().session().getId(), live.get().user(), limit);
      }
      throw idleRefusal(limit);
    }
    if (limit < current) {
      writes.executeWithoutResult(status -> sessions.lowerIdle(principal.sessionUid(), limit));
    }
  }

  /** The live session of {@code token} with its active user; null otherwise. */
  private Live liveSession(String token, Instant now) {
    if (token == null || token.isBlank()) {
      return null;
    }
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
                                .map(user -> new Live(session, user, null))));
    return live == null ? null : live.orElse(null);
  }

  @Override
  public void signOut(String token, boolean idleTimeout) {
    if (token == null || token.isBlank()) {
      return;
    }
    if (idleTimeout) {
      Live live = liveSession(token, clock.instant());
      if (live != null) {
        issuer.endIdle(live.session().getId(), live.user(), live.session().getIdleMinutes());
      }
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
                      // And in each pharmacy this session worked in, beside its sign-in there.
                      for (UUID tenantUid : sessions.enteredPharmacies(session.getId())) {
                        tenantSwitch.within(
                            tenantUid,
                            () -> {
                              audit.record("SIGN_OUT", "User", session.getUserUid(), null, null);
                              return null;
                            });
                      }
                    }));
  }

  @Override
  public void changePassword(UUID userUid, String currentPassword, String newPassword) {
    changePassword(userUid, currentPassword, newPassword, null);
  }

  @Override
  public void changePassword(
      UUID userUid, String currentPassword, String newPassword, String currentToken) {
    Instant now = clock.instant();
    User known =
        reads.execute(
            status ->
                users
                    .findByUid(userUid)
                    .orElseThrow(() -> Refusal.notFound("user_not_found", "No such user.")));
    Passwords.acceptable(newPassword, "newPassword", known.getEmail());
    // The current password first, and a wrong one counts like a failed sign-in: a stolen session
    // must not be a way to guess the password without end (AUTH-5), nor to make the server hash
    // new passwords for nothing (AUTH-6).
    if (!passwords.matches(currentPassword, known.getPasswordHash())) {
      boolean locked = recordFailure(known, known.getEmail(), ClientInfo.UNKNOWN, now, "password");
      if (locked) {
        throw guard.locked(guard.lockFor());
      }
      throw Refusal.invalid(
          "invalid_current_password", "The current password is wrong.", "currentPassword");
    }
    String hash = passwords.hash(newPassword, "newPassword", known.getEmail());
    writes.executeWithoutResult(
        status -> {
          User user =
              users
                  .findByUid(userUid)
                  .orElseThrow(() -> Refusal.notFound("user_not_found", "No such user."));
          if (user.isPasswordChangeRequired()
              && passwords.matches(newPassword, user.getPasswordHash())) {
            throw Refusal.invalid(
                "same_password",
                "Choose a new password of your own, not the one you were given.",
                "newPassword");
          }
          user.changePassword(hash);
          resets.closeOpen(userUid, now);
          int revoked =
              currentToken == null
                  ? 0
                  : sessions.revokeAll(userUid, clock.instant(), Tokens.hash(currentToken.trim()));
          audit.record(
              "PASSWORD_CHANGED", "User", userUid, null, Map.of("otherSessionsRevoked", revoked));
          if (!user.isManaged()) {
            // Told on the person's other channels (NIST SP 800-63B 6.1.2.3), in case it was not
            // them.
            String phone = user.hasVerifiedPhone() ? user.getPhone() : null;
            notices.ifAvailable(
                notice ->
                    notice.passwordChanged(
                        userUid, user.getName(), user.getEmail(), phone, clock.instant()));
          }
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
