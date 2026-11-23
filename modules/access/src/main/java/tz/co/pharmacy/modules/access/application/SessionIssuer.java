package tz.co.pharmacy.modules.access.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tz.co.pharmacy.modules.access.domain.Session;
import tz.co.pharmacy.modules.access.domain.User;
import tz.co.pharmacy.modules.access.persistence.SessionRepository;
import tz.co.pharmacy.modules.access.persistence.UserRepository;
import tz.co.pharmacy.modules.access.ports.inbound.ClientInfo;
import tz.co.pharmacy.modules.access.ports.inbound.Principal;
import tz.co.pharmacy.modules.access.ports.inbound.SignInResult;
import tz.co.pharmacy.modules.access.ports.outbound.AccessNotices;
import tz.co.pharmacy.platform.kernel.Actor;
import tz.co.pharmacy.platform.kernel.ExecutionContext;
import tz.co.pharmacy.platform.kernel.ExecutionContext.Frame;
import tz.co.pharmacy.platform.persistence.TenantContext;
import tz.co.pharmacy.platform.persistence.TenantSwitch;
import tz.co.pharmacy.platform.persistence.audit.AuditLog;

/**
 * Opens sessions, whichever way the user proved who they are (password, two-factor, passkey,
 * invitation). Every sign-in is audited, for the platform and in each pharmacy the user works for
 * (so its administrators see who signed in, and when), and one from a device the user has not used
 * before sends them a notice.
 */
@Component
class SessionIssuer {

  private final UserRepository users;
  private final SessionRepository sessions;
  private final TenantSwitch tenantSwitch;
  private final AuditLog audit;
  private final ObjectProvider<AccessNotices> notices;
  private final TransactionTemplate writes;
  private final Duration sessionLength;
  private final boolean twoFactorRequiredForRoot;
  private final IdlePolicy idle;
  private final tz.co.pharmacy.modules.access.persistence.PasskeyRepository passkeys;

  private final Clock clock;

  SessionIssuer(
      UserRepository users,
      SessionRepository sessions,
      tz.co.pharmacy.modules.access.persistence.PasskeyRepository passkeys,
      TenantSwitch tenantSwitch,
      AuditLog audit,
      ObjectProvider<AccessNotices> notices,
      PlatformTransactionManager transactionManager,
      @Value("${pharmacy.access.session-hours:12}") long sessionHours,
      @Value("${pharmacy.access.require-2fa-for-root:true}") boolean twoFactorRequiredForRoot,
      IdlePolicy idle,
      Clock clock) {
    this.clock = clock;
    this.idle = idle;
    this.users = users;
    this.sessions = sessions;
    this.passkeys = passkeys;
    this.tenantSwitch = tenantSwitch;
    this.audit = audit;
    this.notices = notices;
    this.writes = new TransactionTemplate(transactionManager);
    this.sessionLength = Duration.ofHours(sessionHours);
    this.twoFactorRequiredForRoot = twoFactorRequiredForRoot;
  }

  /** Opens a session for {@code user}; {@code method} is PASSWORD, TWO_FACTOR, PASSKEY... */
  SignInResult issue(User user, ClientInfo client, String method) {
    ClientInfo from = client == null ? ClientInfo.UNKNOWN : client;
    return ExecutionContext.callAs(
        userFrame(user),
        () ->
            TenantContext.callAsUser(
                user.getUid(),
                () ->
                    writes.execute(
                        status -> {
                          Instant now = clock.instant();
                          boolean known = sessions.existsByUserUid(user.getUid());
                          boolean sameDevice =
                              from.userAgent() != null
                                  && sessions.existsByUserUidAndUserAgent(
                                      user.getUid(), from.userAgent());
                          String token = Tokens.newToken();
                          Instant expiresAt = now.plus(sessionLength);
                          Session session =
                              sessions.save(
                                  new Session(
                                      Tokens.hash(token),
                                      user.getUid(),
                                      expiresAt,
                                      trim(from.ip(), 64),
                                      trim(from.userAgent(), 400),
                                      method,
                                      idle.initial(),
                                      provesSecondFactor(method)));
                          User row = users.findByUid(user.getUid()).orElse(user);
                          users.markSignedIn(user.getUid(), now);
                          Map<String, Object> details = new LinkedHashMap<>();
                          details.put("method", method);
                          details.put("ip", from.ip());
                          details.put("userAgent", trim(from.userAgent(), 200));
                          // The platform's record. Each pharmacy records the sign-in when this
                          // session first works there (PharmacyVisits), so none learns when its
                          // staff sign in to work somewhere else.
                          audit.record("SIGN_IN", "User", user.getUid(), null, details);
                          if (known && !sameDevice && from.userAgent() != null) {
                            notices.ifAvailable(
                                notice ->
                                    notice.newSignIn(
                                        user.getUid(),
                                        user.getName(),
                                        user.mailbox(),
                                        Devices.describe(from.userAgent()),
                                        from.ip(),
                                        now));
                          }
                          return new SignInResult(token, expiresAt, principal(row, session));
                        })));
  }

  /**
   * Ends a session that went unused for {@code idleMinutes}, audited as a SIGN_OUT with reason IDLE
   * for the platform and in each pharmacy the session worked in (beside their sign-ins). When
   * several requests find it idle at once, only the one that ends it records it.
   */
  void endIdle(Long sessionId, User user, Integer idleMinutes) {
    // Outside the pharmacy the request may name (the app's own sign-out does), so the first entry
    // is the platform's.
    ExecutionContext.callAs(
        userFrame(user),
        () ->
            TenantContext.callAs(
                null,
                () ->
                    TenantContext.callAsUser(
                        user.getUid(),
                        () ->
                            writes.execute(
                                status -> {
                                  if (sessions.revokeIfLive(sessionId, clock.instant()) == 0) {
                                    return null;
                                  }
                                  Map<String, Object> details = new LinkedHashMap<>();
                                  details.put("reason", "IDLE");
                                  details.put("idleMinutes", idleMinutes);
                                  audit.record("SIGN_OUT", "User", user.getUid(), null, details);
                                  for (UUID tenantUid : sessions.enteredPharmacies(sessionId)) {
                                    tenantSwitch.within(
                                        tenantUid,
                                        () -> {
                                          audit.record(
                                              "SIGN_OUT", "User", user.getUid(), null, details);
                                          return null;
                                        });
                                  }
                                  return null;
                                }))));
  }

  /**
   * The signed-in user of {@code session}. The second factor is what this session proved, not what
   * the account has set up: a password-only sign-in of an account that also has a passkey has no
   * second factor (adversarial review AUTH-2).
   */
  Principal principal(User user, Session session) {
    return new Principal(
        user.getUid(),
        user.getName(),
        user.getEmail(),
        user.isRootAdmin(),
        twoFactorRequired(user) && !user.isTwoFactorEnabled(),
        user.isPasswordChangeRequired(),
        session.getUid(),
        session.isSecondFactor(),
        session.getVerifiedAt());
  }

  /** A sign-in by an authenticator code or a (user-verified) passkey proved two factors. */
  static boolean provesSecondFactor(String method) {
    return "TWO_FACTOR".equals(method) || "PASSKEY".equals(method);
  }

  boolean twoFactorRequired(User user) {
    return twoFactorRequiredForRoot && user.isRootAdmin();
  }

  /** The frame of a user acting on their own account, on a route that may have no session yet. */
  static Frame userFrame(User user) {
    String correlation = ExecutionContext.correlationId().orElse(UUID.randomUUID().toString());
    return new Frame(
        Actor.user(user.getUid(), user.getName(), user.getEmail()),
        correlation,
        null,
        null,
        Set.of(),
        null,
        user.isRootAdmin());
  }

  /** The frame of an anonymous step of access, such as starting a passkey sign-in. */
  static Frame serviceFrame() {
    return Frame.service(
        "access-sign-in",
        ExecutionContext.correlationId().orElse(UUID.randomUUID().toString()),
        null);
  }

  static String trim(String value, int max) {
    if (value == null) {
      return null;
    }
    return value.length() > max ? value.substring(0, max) : value;
  }
}
